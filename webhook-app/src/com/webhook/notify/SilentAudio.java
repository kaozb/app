package com.webhook.notify;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * 静音音频发生器：持续输出 0 音量 PCM 数据，让系统把本进程当作「正在播放音乐」的音频应用。
 * 华为/鸿蒙等 ROM 息屏后会冻结普通后台进程，但对正在输出音频的进程有豁免，
 * 因此这类进程锁屏后仍能 accept 新的 TCP 连接、及时处理短信广播。
 */
final class SilentAudio {

    private AudioTrack track;
    private Thread worker;
    private volatile boolean running;

    synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                // 低采样率单声道即可维持音频活跃，功耗最低
                int rate = 8000;
                try {
                    int min = AudioTrack.getMinBufferSize(rate,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    int size = Math.max(min, 2048) * 2;
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(rate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setBufferSizeInBytes(size)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    track.setVolume(0f);
                    track.play();
                    byte[] silence = new byte[size];
                    while (running) {
                        // 持续写入静音数据（全 0），保持音频流活跃
                        if (track.write(silence, 0, silence.length) < 0) break;
                    }
                } catch (Exception ignored) {
                } finally {
                    releaseTrack();
                }
            }
        }, "silent-audio");
        worker.start();
    }

    synchronized void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.interrupt();
            } catch (Exception ignored) {
            }
            worker = null;
        }
        releaseTrack();
    }

    private synchronized void releaseTrack() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) t.stop();
            } catch (Exception ignored) {
            }
            try {
                t.release();
            } catch (Exception ignored) {
            }
        }
    }
}
