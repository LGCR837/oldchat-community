package com.im.oldchat.service;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;

/** 隔离 API 21 音频属性，避免旧 Dalvik 校验 MusicPlaybackService 时缺类。 */
@TargetApi(21)
final class MusicPlaybackApi21 {
    private MusicPlaybackApi21() { }

    static void configure(MediaPlayer player) {
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
    }
}
