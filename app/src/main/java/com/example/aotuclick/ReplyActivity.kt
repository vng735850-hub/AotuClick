package com.example.autoclick

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Hộp nhập chữ nhỏ để trả lời nhanh tin nhắn, dùng nút trả lời có sẵn trong thông báo gốc. */
class ReplyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = IslandBus.replyAction
        if (action == null) {
            finish()
            return
        }
        val d = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())

        val head = TextView(this)
        head.text = "Trả lời: " + IslandBus.replyLabel
        head.textSize = 15f
        root.addView(head)

        val et = EditText(this)
        et.hint = "Nhập tin nhắn trả lời"
        et.minLines = 2
        root.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val send = Button(this)
        send.text = "Gửi"
        send.setOnClickListener {
            val msg = et.text.toString().trim()
            if (msg.isEmpty()) {
                Toast.makeText(this, "Chưa nhập nội dung", Toast.LENGTH_SHORT).show()
            } else {
                sendReply(msg)
            }
        }
        root.addView(send)

        setContentView(root)
        et.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun sendReply(msg: String) {
        val action = IslandBus.replyAction
        try {
            val inputs = action?.remoteInputs
            val pi = action?.actionIntent
            if (inputs == null || pi == null) {
                Toast.makeText(this, "Không gửi được: thông báo đã hết hạn", Toast.LENGTH_LONG).show()
            } else {
                val results = Bundle()
                for (r in inputs) results.putCharSequence(r.resultKey, msg)
                val i = Intent()
                RemoteInput.addResultsToIntent(inputs, i, results)
                pi.send(this, 0, i)
                Toast.makeText(this, "Đã gửi", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Không gửi được: " + e.javaClass.simpleName, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
