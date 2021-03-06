package org.easydarwin.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Process;
import android.preference.PreferenceManager;
import android.util.Log;

import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.push.Pusher;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;


public class AudioStream {
    private static AudioStream _this;
    private final Context context;
    EasyMuxer muxer;
    private int samplingRate = 8000;
    private int bitRate = 16000;
    private int BUFFER_SIZE = 1920;
    int mSamplingRateIndex = 0;
    AudioRecord mAudioRecord;
    MediaCodec mMediaCodec;
    private Thread mThread = null;
    String TAG = "AudioStream";
    //final String path = Environment.getExternalStorageDirectory() + "/123450001.aac";

    protected MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    protected ByteBuffer[] mBuffers = null;
    Set<Pusher> sets = new HashSet<>();
    /**
     * There are 13 supported frequencies by ADTS.
     **/
    public static final int[] AUDIO_SAMPLING_RATES = {96000, // 0
            88200, // 1
            64000, // 2
            48000, // 3
            44100, // 4
            32000, // 5
            24000, // 6
            22050, // 7
            16000, // 8
            12000, // 9
            11025, // 10
            8000, // 11
            7350, // 12
            -1, // 13
            -1, // 14
            -1, // 15
    };
    private Thread mWriter;
    private MediaFormat newFormat;

    public AudioStream(Context context) {
        this.context = context;
        int i = 0;
        for (; i < AUDIO_SAMPLING_RATES.length; i++) {
            if (AUDIO_SAMPLING_RATES[i] == samplingRate) {
                mSamplingRateIndex = i;
                break;
            }
        }
    }

    public void addPusher(Pusher pusher){
        boolean shouldStart =false;
        synchronized (this){
            if (sets.isEmpty())
                shouldStart = true;
            sets.add(pusher);
        }
        if (shouldStart) startRecord();
    }

    public void removePusher(Pusher pusher){
        boolean shouldStop = false;
        synchronized (this){
            sets.remove(pusher);
            if (sets.isEmpty())
                shouldStop = true;
        }
        if (shouldStop) stop();
    }

    /**
     * 编码
     */
    private void startRecord() {

        if (mThread != null) return;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                int len = 0, bufferIndex = 0;
                try {
                    int bufferSize = AudioRecord.getMinBufferSize(samplingRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, samplingRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                    mMediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
                    MediaFormat format = new MediaFormat();
                    format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
                    format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
                    format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
                    format.setInteger(MediaFormat.KEY_SAMPLE_RATE, samplingRate);
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE);
                    mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    mMediaCodec.start();


                    mWriter = new WriterThread();
                    mWriter.start();
                    mAudioRecord.startRecording();
                    final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();

                    long presentationTimeUs = 0;
                    while (mThread != null) {
                        bufferIndex = mMediaCodec.dequeueInputBuffer(1000);
                        if (bufferIndex >= 0) {
                            ByteBuffer audioBuffer = inputBuffers[bufferIndex];
                            audioBuffer.clear();
                            long timeUs = System.nanoTime() / 1000;
                            len = mAudioRecord.read(audioBuffer, BUFFER_SIZE);
                            Log.i(TAG, String.format("audio: %d [%d] ", timeUs, timeUs - presentationTimeUs));
                            presentationTimeUs = timeUs;

                            EasyMuxer m = muxer;
                            if (m != null && len >0) {
                                byte []bytes = new byte[len];
                                audioBuffer.clear();
                                audioBuffer.get(bytes);
                                audioBuffer.clear();
                                m.pumpPCMStream(bytes, timeUs/1000);
                            }
                            if (len == AudioRecord.ERROR_INVALID_OPERATION || len == AudioRecord.ERROR_BAD_VALUE) {
                                mMediaCodec.queueInputBuffer(bufferIndex, 0, 0, presentationTimeUs, 0);
                            } else {
                                mMediaCodec.queueInputBuffer(bufferIndex, 0, len, presentationTimeUs, 0);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Record___Error!!!!!");
                    e.printStackTrace();
                } finally {
                    Thread t = mWriter;
                    mWriter = null;
                    while (t != null && t.isAlive()) {
                        try {
                            t.interrupt();
                            t.join();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    ;
                    if (mAudioRecord != null) {
                        mAudioRecord.stop();
                        mAudioRecord.release();
                        mAudioRecord = null;
                    }
                    if (mMediaCodec != null) {
                        mMediaCodec.stop();
                        mMediaCodec.release();
                        mMediaCodec = null;
                    }
                }
            }
        }, "AACRecoder");
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean("key-enable-audio",true)) {
            mThread.start();
        }
    }


    public synchronized void setMuxer(EasyMuxer muxer) {
        if (muxer != null) {
            MediaFormat format = new MediaFormat();
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, samplingRate);
            format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            muxer.addTrack(format, false);
        }
        this.muxer = muxer;
    }

    public static synchronized AudioStream getInstance() {
//        EasyApplication app = EasyApplication.getEasyApplication();
//        if (_this == null) _this = new AudioStream(app);
        return _this;
    }

    private class WriterThread extends Thread {

        public WriterThread() {
            super("WriteAudio");
        }

        @Override
        public void run() {
            int index = 0;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            } else {
                mBuffers = mMediaCodec.getOutputBuffers();
            }
            ByteBuffer mBuffer = ByteBuffer.allocate(10240);
            do {
                index = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 10000);
                if (index >= 0) {
                    if (mBufferInfo.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                        continue;
                    }
                    mBuffer.clear();
                    ByteBuffer outputBuffer = null;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        outputBuffer = mMediaCodec.getOutputBuffer(index);
                    } else {
                        outputBuffer = mBuffers[index];
                    }

                    outputBuffer.get(mBuffer.array(), 7, mBufferInfo.size);
                    outputBuffer.clear();
                    mBuffer.position(7 + mBufferInfo.size);
                    addADTStoPacket(mBuffer.array(), mBufferInfo.size + 7);
                    mBuffer.flip();
                    Collection<Pusher> p;
                    synchronized (AudioStream.this){
                        p = sets;
                    }
                    Iterator<Pusher> it = p.iterator();
                    while (it.hasNext()){
                        Pusher ps = it.next();
                        ps.push(mBuffer.array(), 0, mBufferInfo.size + 7, mBufferInfo.presentationTimeUs / 1000, 0);
                    }

                    mMediaCodec.releaseOutputBuffer(index, false);
                } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    mBuffers = mMediaCodec.getOutputBuffers();
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (AudioStream.this) {
                        Log.v(TAG, "output format changed...");
                        newFormat = mMediaCodec.getOutputFormat();
                    }
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    Log.v(TAG, "No buffer available...");
                } else {
                    Log.e(TAG, "Message: " + index);
                }
            } while (mWriter != null);
        }
    }

    private void addADTStoPacket(byte[] packet, int packetLen) {
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF1;
        packet[2] = (byte) (((2 - 1) << 6) + (mSamplingRateIndex << 2) + (1 >> 2));
        packet[3] = (byte) (((1 & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    private void stop() {
        try {
            Thread t = mThread;
            mThread = null;
            if (t != null) {
                t.interrupt();
                t.join();
            }
        } catch (InterruptedException e) {
            e.fillInStackTrace();
        }
    }

}
