package com.example.screepcap.manager;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 屏幕录制管理器
 * 负责视频的采集、编码和输出
 * 使用单例模式确保全局只有一个视频录制实例
 */
public class ScreenRecordManager {
    private static final String TAG = "ScreenRecordManager";

    // MediaCodec 编码超时时间，单位：微秒
    private static final int TIMEOUT_US = 10000;

    // 屏幕捕获相关
    private MediaProjection mediaProjection;    // 用于获取屏幕内容
    private VirtualDisplay virtualDisplay;      // 虚拟显示器，用于承载屏幕内容
    
    // 视频编码相关
    private MediaCodec encoder;                 // 视频编码器
    private Surface inputSurface;              // 输入surface，接收屏幕内容

    // 视频参数
    private int bitRate = 5 * 1024 * 1024;     // 比特率
    private int frameRate = 30;                // 帧率
    private int iFrameInterval = 1;            // I帧间隔

    // 屏幕参数
    private int screenWidth;                    // 屏幕宽度
    private int screenHeight;                   // 屏幕高度
    private int screenDensity;                  // 屏幕密度
    
    // 状态标志
    private final AtomicBoolean isRecording = new AtomicBoolean(false);          // 是否正在录制
    private int videoTrackIndex = -1;          // 视频轨道索引
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);  // 是否正在处理视频帧
    private final AtomicBoolean isPrepared = new AtomicBoolean(false);    // 是否已经准备好编码器
    
    private Context context;             // 上下文，用于权限检查
    // 单例实例
    private static volatile ScreenRecordManager instance;
    private VideoDataCallback callback;
    private RecordStateCallback stateCallback;

    private ScreenRecordManager() {
    }

    public static ScreenRecordManager getInstance() {
        if (instance == null) {
            synchronized (ScreenRecordManager.class) {
                if (instance == null) {
                    instance = new ScreenRecordManager();
                }
            }
        }
        return instance;
    }

    /**
     * 设置屏幕录制配置
     * @param projection MediaProjection 对象
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param density 屏幕密度
     */
    private void setConfig(MediaProjection projection, int width, int height, int density) {
        mediaProjection = projection;
        screenWidth = width;
        screenHeight = height;
        screenDensity = density;
    }

    private void setContext(Context context) {
        this.context = context;
    }

    /**
     * 设置视频参数
     * @param bitRate 比特率
     * @param frameRate 帧率
     * @param iFrameInterval I帧间隔
     */
    private void setVideoParams(int bitRate, int frameRate, int iFrameInterval) {
        this.bitRate = bitRate;
        this.frameRate = frameRate;
        this.iFrameInterval = iFrameInterval;
    }

    /**
     * 设置视频数据回调
     */
    public interface VideoDataCallback {
        void onVideoData(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo);
    }

    private void setCallback(VideoDataCallback callback) {
        this.callback = callback;
    }

    /**
     * 录制状态回调接口
     */
    public interface RecordStateCallback {
        void onPrepared();              // 编码器准备完成
        void onStart();                 // 录制开始
        void onStop();                  // 录制停止
        void onError(String error);     // 发生错误
    }

    private void setStateCallback(RecordStateCallback callback) {
        this.stateCallback = callback;
    }

    private void notifyError(String error) {
        if (stateCallback != null) {
            stateCallback.onError(error);
        }
        Log.e(TAG, error);
    }

    /**
     * 准备视频编码器
     * @return 是否成功准备编码器
     */
    private boolean prepare() {
        try {
            // 准备视频编码器
            MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 
                    screenWidth, screenHeight);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();
            isPrepared.set(true);
            if (stateCallback != null) {
                stateCallback.onPrepared();
            }
            return true;
        } catch (IOException e) {
            String error = "prepareEncoder error: " + e.getMessage();
            notifyError(error);
            releaseEncoderResources();
            return false;
        }
    }

    /**
     * 创建虚拟显示器
     */
    private void createVirtualDisplay() {
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecording",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
        );
    }

    /**
     * 启动视频编码线程
     */
    private void startEncodingLoop() {
        new Thread(() -> {
            try {
                while (isProcessing.get()) {
                    if (!encodeFrame()) {
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in encoding loop", e);
            } finally {
                releaseEncoderResources();
                Log.i(TAG, "Encoding thread finished");
            }
        }, "EncodingThread").start();
    }
    
    /**
     * 编码视频帧
     * @return 是否成功编码帧
     */
    private boolean encodeFrame() {
        if (encoder == null || !isRecording.get() || !isProcessing.get()) {
            return false;
        }

        try {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

            if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return true;
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = encoder.getOutputFormat();
                return true;
            } else if (outputBufferId < 0) {
                Log.w(TAG, "Unexpected result from encoder.dequeueOutputBuffer: " + outputBufferId);
                return true;
            }

            ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferId);
            if (encodedData == null) {
                return false;
            }

            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                bufferInfo.size = 0;
            }

            if (callback != null && bufferInfo.size > 0) {
                callback.onVideoData(encodedData, bufferInfo);
            }

            encoder.releaseOutputBuffer(outputBufferId, false);
            return true;
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "Codec error: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 释放视频编码资源
     */
    private void releaseEncoderResources() {
        Log.d(TAG, "releaseEncoderResources");
        videoTrackIndex = -1;
        isPrepared.set(false);

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.e(TAG, "releaseEncoderResources encoder error: " + e.getMessage());
            }
            encoder = null;
        }

        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    /**
     * 开始录制
     */
    private void startRecording() {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording");
            return;
        }

        try {
            if (!isPrepared.get() && !prepare()) {
                notifyError("Failed to prepare encoder");
                return;
            }
            createVirtualDisplay();
            isRecording.set(true);
            isProcessing.set(true);
            startEncodingLoop();
            if (stateCallback != null) {
                stateCallback.onStart();
            }
        } catch (Exception e) {
            notifyError("Failed to start recording: " + e.getMessage());
            stopRecording();
        }
    }

    /**
     * 停止录制
     */
    private void stopRecording() {
        if (!isRecording.get()) {
            Log.w(TAG, "Not recording");
            return;
        }
        isRecording.set(false);
        isProcessing.set(false);
        try {
            if (encoder != null){
                encoder.signalEndOfInputStream();
                // Give some time for the encoder to process remaining frames
                Thread.sleep(500);
            }
            if (stateCallback != null) {
                stateCallback.onStop();
            }
        } catch (Exception e) {
            notifyError("Failed to stop recording: " + e.getMessage());
        }

        releaseEncoderResources();
    }

    /**
     * 获取视频格式
     * @return 视频MediaFormat，如果编码器未初始化则返回null
     */
    private MediaFormat getVideoFormat() {
        if (encoder != null) {
            return encoder.getOutputFormat();
        }
        return null;
    }

    /**
     * 提供给外部的视频录制控制接口
     */
    public static class Controller {
        /**
         * 初始化录制
         * @param context 应用上下文
         * @param projection MediaProjection对象
         * @param width 屏幕宽度
         * @param height 屏幕高度
         * @param density 屏幕密度
         * @return 是否初始化成功
         */
        public static boolean init(Context context, MediaProjection projection, int width, int height, int density) {
            ScreenRecordManager manager = getInstance();
            manager.setContext(context);
            manager.setConfig(projection, width, height, density);
            return true;
        }

        /**
         * 开始录制
         */
        public static void start() {
            getInstance().startRecording();
        }

        /**
         * 停止录制
         */
        public static void stop() {
            getInstance().stopRecording();
        }

        /**
         * 获取视频格式
         */
        public static MediaFormat getFormat() {
            return getInstance().getVideoFormat();
        }

        /**
         * 释放资源
         */
        public static void release() {
            getInstance().releaseEncoderResources();
        }

        /**
         * 设置视频参数
         */
        public static void setVideoParams(int bitRate, int frameRate, int iFrameInterval) {
            getInstance().setVideoParams(bitRate, frameRate, iFrameInterval);
        }

        /**
         * 设置视频数据回调
         */
        public static void setCallback(VideoDataCallback callback) {
            getInstance().setCallback(callback);
        }

        /**
         * 设置录制状态回调
         */
        public static void setStateCallback(RecordStateCallback callback) {
            getInstance().setStateCallback(callback);
        }
    }
}