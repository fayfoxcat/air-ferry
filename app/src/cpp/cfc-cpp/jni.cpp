#include "MultiThreadedDecoder.h"
#include "cimb_translator/CimbDecoder.h"
#include "cimb_translator/CimbReader.h"
#include "encoder/Decoder.h"
#include "extractor/Scanner.h"
#include "serialize/format.h"

#include <jni.h>
#include <android/log.h>
#include <opencv2/core/core.hpp>
#include <opencv2/core/ocl.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <memory>
#include <mutex>

#define TAG "CimbarCPP"

using namespace std;
using namespace cv;

namespace {
	std::shared_ptr<MultiThreadedDecoder> _proc;
	std::mutex _mutex; // for _proc
	std::set<std::string> _completed;

	unsigned _calls = 0;

	// 传输状态跟踪（与上游逻辑保持一致）：
	//   0 = 无解码活动
	//   1 = 部分解码（部分帧解码成功）
	//   2 = 完整解码（一个完整的 fountain 块解码完成）
	int _transferStatus = 0;
	clock_t _frameDecodeSnapshot = 0;
	clock_t _frameSuccessSnapshot = 0;

	void drawGuidance(cv::Mat& mat, int in_progress, int rotationDeg)
	{
		int minsz = std::min(mat.cols, mat.rows);
		int guideWidth = minsz >> 7;
		int outlineWidth = guideWidth + (minsz >> 8);
		int guideLength = guideWidth << 3;
		int guideOffset = minsz >> 4;
		int outlineOffset = (outlineWidth - guideWidth) >> 1;

		cv::Scalar color = cv::Scalar(255,255,255);
		if (in_progress == 1)
			color = cv::Scalar(255,244,94);
		else if (in_progress == 2)
			color = cv::Scalar(0,255,0);
		cv::Scalar outline = cv::Scalar(0,0,0);

		// Normalize rotation to 0/90/180/270
		int rot = ((rotationDeg % 360) + 360) % 360;

		// When the device is rotated, the camera frame stays in its natural
		// orientation (landscape), but the user is holding the phone sideways.
		// We rotate the canvas so the corner guides appear upright to the user,
		// draw the guides, then rotate back.
		cv::Point2f center(mat.cols / 2.0f, mat.rows / 2.0f);
		cv::Mat rotMat, rotMatInv;
		bool needsRotation = (rot != 0);
		if (needsRotation)
		{
			// Positive angle = CCW in OpenCV convention.
			// Device rotated CW by `rot` degrees → we rotate canvas CCW by `rot`.
			rotMat    = cv::getRotationMatrix2D(center,  (double)rot, 1.0);
			rotMatInv = cv::getRotationMatrix2D(center, -(double)rot, 1.0);
			cv::warpAffine(mat, mat, rotMat, mat.size(), cv::INTER_LINEAR,
			               cv::BORDER_CONSTANT, cv::Scalar(0,0,0));
		}

		int xextra = 0;
		if (mat.cols > mat.rows)
			xextra = (mat.cols - mat.rows) >> 1;
		int yextra = 0;
		if (mat.rows > mat.cols)
			yextra = (mat.rows - mat.cols) >> 1;

		// top-left
		int lx = guideOffset + xextra;
		int ty = guideOffset + yextra;
		int outlinex = lx - outlineOffset;
		int outliney = ty - outlineOffset;
		cv::line(mat, cv::Point(lx, outliney), cv::Point(lx + guideLength, outliney), outline, outlineWidth);
		cv::line(mat, cv::Point(outlinex, ty), cv::Point(outlinex, ty + guideLength), outline, outlineWidth);
		cv::line(mat, cv::Point(lx, ty), cv::Point(lx + guideLength, ty), color, guideWidth);
		cv::line(mat, cv::Point(lx, ty), cv::Point(lx, ty + guideLength), color, guideWidth);

		// top-right
		int rx = mat.cols - guideOffset - guideWidth - xextra;
		outlinex = rx + outlineOffset;
		outliney = ty - outlineOffset;
		cv::line(mat, cv::Point(rx, outliney), cv::Point(rx - guideLength, outliney), outline, outlineWidth);
		cv::line(mat, cv::Point(outlinex, ty), cv::Point(outlinex, ty + guideLength), outline, outlineWidth);
		cv::line(mat, cv::Point(rx, ty), cv::Point(rx - guideLength, ty), color, guideWidth);
		cv::line(mat, cv::Point(rx, ty), cv::Point(rx, ty + guideLength), color, guideWidth);

		// bottom-left
		int by = mat.rows - guideOffset - guideWidth - yextra;
		outlinex = lx - outlineOffset;
		outliney = by + outlineOffset;
		cv::line(mat, cv::Point(lx, outliney), cv::Point(lx + guideLength, outliney), outline, outlineWidth);
		cv::line(mat, cv::Point(outlinex, by), cv::Point(outlinex, by - guideLength), outline, outlineWidth);
		cv::line(mat, cv::Point(lx, by), cv::Point(lx + guideLength, by), color, guideWidth);
		cv::line(mat, cv::Point(lx, by), cv::Point(lx, by - guideLength), color, guideWidth);

		if (needsRotation)
		{
			// Rotate the canvas back so the camera frame is unaffected
			cv::warpAffine(mat, mat, rotMatInv, mat.size(), cv::INTER_LINEAR,
			               cv::BORDER_CONSTANT, cv::Scalar(0,0,0));
		}
	}

	std::string jstring_to_cppstr(JNIEnv *env, const jstring& dataPathObj)
	{
		const char* temp = env->GetStringUTFChars(dataPathObj, NULL);
		string res(temp);
		env->ReleaseStringUTFChars(dataPathObj, temp);
		return res;
	}
}

extern "C" {
jstring JNICALL
Java_cc_asac_cimbar_ReceiverFragment_processImageJNI(JNIEnv *env, jobject instance, jlong matAddr, jstring dataPathObj, jint modeInt, jint rotationDeg)
{
	++_calls;

	// get params from raw address
	Mat &mat = *(Mat *) matAddr;
	string dataPath = jstring_to_cppstr(env, dataPathObj);
	int modeVal = (int)modeInt;
	int rotation = (int)rotationDeg;

	std::shared_ptr<MultiThreadedDecoder> proc;
	{
		std::lock_guard<std::mutex> lock(_mutex);
		if (!_proc or !_proc->set_mode(modeVal))
			_proc = std::make_shared<MultiThreadedDecoder>(dataPath, modeVal);
		proc = _proc;
	}

	cv::Mat img = mat.clone();
	proc->add(img);

	// 每 32 帧更新一次传输状态（与上游节奏相同）。
	// _transferStatus 供 Java 层驱动 UI 反馈。
	if (_calls & 31)
	{
		clock_t decodeSnapshot = proc->decoded;
		clock_t perfectSnapshot = proc->perfect;
		_transferStatus = (perfectSnapshot > _frameSuccessSnapshot) ? 1 : 0;
		_transferStatus += (decodeSnapshot > _frameDecodeSnapshot) ? 1 : 0;
		_frameDecodeSnapshot = decodeSnapshot;
		_frameSuccessSnapshot = perfectSnapshot;
	}


		// Draw L-shaped corner guides on the camera frame (triangular finder pattern feedback)
		drawGuidance(mat, _transferStatus, rotation);

	// 构建复合结果字符串。
	// 格式：[文件名或模式前缀][|~进度]
	//
	// 示例：
	//   ""          -> 空闲，无进度
	//   "~42"       -> 42% 进度，无模式/文件事件
	//   "/68"       -> 检测到模式（68），尚无进度
	//   "/68|~42"   -> 检测到模式 且 42% 进度
	//   "filename"  -> 文件接收完成
	//
	// 将进度上报与模式检测解耦，使 Java 层无论 detected_mode() 状态如何，
	// 都能始终收到进度更新。

	string modeOrFile;

	// 优先级 1：文件接收完成（覆盖所有其他情况）
	std::vector<string> all_decodes = proc->get_done();
	for (string& s : all_decodes)
		if (_completed.find(s) == _completed.end())
		{
			_completed.insert(s);
			modeOrFile = s;
		}

	// 优先级 2：模式检测信号（仅在本帧无文件完成时上报）
	if (modeOrFile.empty() && proc->detected_mode())
		modeOrFile = fmt::format("/{}", proc->detected_mode());

	// 始终追加进度（以 '|' 分隔），使 Java 层在模式检测期间也能显示进度
	string progressStr;
	std::vector<double> progress = proc->get_progress();
	if (!progress.empty())
	{
		double maxP = *std::max_element(progress.begin(), progress.end());
		int pct = (int)(maxP * 100);
		if (pct > 0)
			// 格式：~进度%:已解码字节数，例如 ~42:153600
			progressStr = fmt::format("~{}:{}", pct, (long long)proc->bytes);
	}

	string result;
	if (!modeOrFile.empty() && !progressStr.empty())
		result = modeOrFile + "|" + progressStr;
	else if (!modeOrFile.empty())
		result = modeOrFile;
	else
		result = progressStr;

	return env->NewStringUTF(result.c_str());
}

void JNICALL
Java_cc_asac_cimbar_ReceiverFragment_shutdownJNI(JNIEnv *env, jobject instance) {
	__android_log_print(ANDROID_LOG_INFO, TAG, "Shutdown cfc-cpp\n");

	std::lock_guard<std::mutex> lock(_mutex);
	if (_proc)
		_proc->stop();
	_proc = nullptr;
	_completed.clear();
	_transferStatus = 0;
	_frameDecodeSnapshot = 0;
	_frameSuccessSnapshot = 0;
	MultiThreadedDecoder::bytes = 0;
}

void JNICALL
Java_cc_asac_cimbar_ReceiverFragment_resetCompletedJNI(JNIEnv *env, jobject instance, jstring filenameObj) {
	string filename = jstring_to_cppstr(env, filenameObj);
	std::lock_guard<std::mutex> lock(_mutex);
	_completed.erase(filename);
}

}
