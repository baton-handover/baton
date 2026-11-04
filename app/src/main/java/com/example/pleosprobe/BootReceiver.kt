package com.example.pleosprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 부팅·사용자 잠금 해제 수신 여부를 남기고 기록 서비스를 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} R 수신 ${intent.action} user=${android.os.Process.myUserHandle()}"
        Log.i("PleosProbe", line)
        runCatching { File(context.filesDir, "probe_log.txt").appendText(line + "\n") }
        runCatching { context.startForegroundService(Intent(context, RecorderService::class.java).putExtra("from", intent.action)) }
            .onFailure {
                Log.i("PleosProbe", "R 서비스 시작 실패: $it")
                runCatching { File(context.filesDir, "probe_log.txt").appendText("R 서비스 시작 실패: $it\n") }
            }
    }
}
