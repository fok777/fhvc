package com.termux.zerocore.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.xh_lib.utils.UUtils
import com.termux.R

public open class BaseTitleActivity: AppCompatActivity() {
    private var mTitle: TextView? = null
    private var mCancel: ImageView? = null
    private var mBaseFrame: FrameLayout? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(layoutResID: Int) {
        val childView = LayoutInflater.from(this).inflate(layoutResID, null)
        setContentView(childView)
    }

    /**
     * 代码搭 UI 的页面走这个重载。
     *
     * 基类若只重写 setContentView(Int)，直接 setContentView(View) 会落到
     * AppCompatActivity，标题栏容器不建立、mTitle 为 null，随后 setBaseTitle 必崩。
     * 这里复用同一套包装，两个入口行为一致。
     */
    override fun setContentView(view: View) {
        val thisView = LayoutInflater.from(this).inflate(R.layout.activity_base_title, null)
        mTitle = thisView.findViewById<TextView>(R.id.title)
        mBaseFrame = thisView.findViewById<FrameLayout>(R.id.base_frame)
        mCancel = thisView.findViewById<ImageView>(R.id.cancel)
        mCancel!!.setOnClickListener { finish() }
        mBaseFrame!!.addView(view)
        // 必须显式调用 super，否则会递归回本方法
        super.setContentView(thisView)
    }

    public fun setBaseTitle(title: String) {
        mTitle!!.text = title
    }

    public fun goneCancelButton() {
        mCancel!!.visibility = View.GONE
    }
}
