package com.devtools.touchsim

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(TextView(this).apply {
            text = "TouchSim\n\n" +
                "1) اضغط «فتح إعدادات إمكانية الوصول» وفعّل خدمة TouchSim.\n" +
                "2) ستظهر أيقونة + عائمة على الشاشة (يمكن سحبها).\n" +
                "3) اضغط + لإضافة زر وتحديد نوعه وحجمه ولونه وشفافيته.\n" +
                "4) اضغط على الزر لتشغيله، ولإيقاف كل الأزرار اضغط على الأيقونة ■.\n" +
                "5) ضغطة مطولة على أي زر لتعديله أو حذفه."
            textSize = 16f
        })

        root.addView(Button(this).apply {
            text = "فتح إعدادات إمكانية الوصول"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "إظهار الأزرار العائمة"
            setOnClickListener {
                val s = TouchService.instance
                if (s == null) {
                    Toast.makeText(this@MainActivity, "فعّل الخدمة أولاً من إعدادات إمكانية الوصول", Toast.LENGTH_LONG).show()
                } else {
                    s.showControls()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "إخفاء الأزرار العائمة"
            setOnClickListener { TouchService.instance?.hideControls() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
