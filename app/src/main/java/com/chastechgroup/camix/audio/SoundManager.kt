package com.chastechgroup.camix.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.chastechgroup.camix.R
import timber.log.Timber

/**
 * Manages all camera UI sounds using SoundPool for low-latency playback.
 * Every button action gets audio feedback.
 */
class SoundManager(private val context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private var shutterSoundId       = 0
    private var videoStartSoundId    = 0
    private var videoStopSoundId     = 0
    private var focusLockSoundId     = 0
    private var timelapseSoundId     = 0
    private var photoSavedSoundId    = 0
    private var aeAfLockSoundId      = 0
    private var modeSwitchSoundId    = 0

    init {
        load()
    }

    private fun load() {
        try {
            shutterSoundId    = pool.load(context, R.raw.shutter,          1)
            videoStartSoundId = pool.load(context, R.raw.video_start,      1)
            videoStopSoundId  = pool.load(context, R.raw.video_stop,       1)
            focusLockSoundId  = pool.load(context, R.raw.focus_lock,       1)
            timelapseSoundId  = pool.load(context, R.raw.timelapse_frame,  1)
            photoSavedSoundId = pool.load(context, R.raw.photo_saved,      1)
            aeAfLockSoundId   = pool.load(context, R.raw.ae_af_lock,       1)
            modeSwitchSoundId = pool.load(context, R.raw.mode_switch,      1)
            Timber.d("SoundManager: all sounds loaded")
        } catch (e: Exception) {
            Timber.e(e, "SoundManager: load failed")
        }
    }

    fun playShutter()       = play(shutterSoundId,    1.0f)
    fun playVideoStart()    = play(videoStartSoundId, 0.9f)
    fun playVideoStop()     = play(videoStopSoundId,  0.9f)
    fun playFocusLock()     = play(focusLockSoundId,  0.7f)
    fun playTimelapseFrame()= play(timelapseSoundId,  0.5f)
    fun playPhotoSaved()    = play(photoSavedSoundId, 0.85f)
    fun playAeAfLock()      = play(aeAfLockSoundId,   0.8f)
    fun playModeSwitch()    = play(modeSwitchSoundId, 0.6f)

    private fun play(soundId: Int, volume: Float) {
        if (soundId == 0) return
        try {
            pool.play(soundId, volume, volume, 1, 0, 1.0f)
        } catch (e: Exception) {
            Timber.e(e, "SoundManager: play failed id=$soundId")
        }
    }

    fun release() {
        try { pool.release() } catch (_: Exception) { }
        Timber.d("SoundManager released")
    }
}
