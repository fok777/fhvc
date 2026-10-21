package com.termux.zerocore.ai.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 消息长按菜单：复制、删除、重新编辑重发、在之后插入。
 *
 * 挂在系统文本选择的 ActionMode 上，而不是给气泡单独加长按监听——
 * 消息 TextView 开了 textIsSelectable，长按事件会被它吃掉，父容器收不到。
 * 往系统菜单里追加项既能保留原生「选中复制」，又能拿到自定义操作。
 */
object ZtMessageActions {

    private const val MENU_COPY_ALL = 1
    private const val MENU_DELETE = 2
    private const val MENU_EDIT = 3
    private const val MENU_INSERT = 4

    private const val ROLE_USER = "user"

    interface Host {
        fun deleteMessageAt(index: Int)
        fun resendFrom(index: Int, newText: String)
        fun insertAfter(index: Int, text: String)
    }

    /**
     * 给消息 TextView 挂上自定义菜单项。
     * [index] 是该条在会话历史里的下标，删除 / 重发都按它定位。
     */
    fun attach(content: TextView, role: String, text: String, index: Int, host: Host) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        content.setCustomSelectionActionModeCallback(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_COPY_ALL, 100, "复制全文")
                menu.add(Menu.NONE, MENU_DELETE, 101, "删除此条")
                if (role == ROLE_USER) {
                    menu.add(Menu.NONE, MENU_EDIT, 102, "重新编辑并重发")
                }
                menu.add(Menu.NONE, MENU_INSERT, 103, "在此之后插入")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    MENU_COPY_ALL -> copyAll(content, text)
                    MENU_DELETE -> host.deleteMessageAt(index)
                    MENU_EDIT -> promptEdit(content.context, text) { host.resendFrom(index, it) }
                    MENU_INSERT -> promptEdit(content.context, "", "插入的消息") {
                        host.insertAfter(index, it)
                    }
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {}
        })
    }

    private fun copyAll(content: TextView, text: String) {
        val ctx = content.context
        val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("message", text))
    }

    private fun promptEdit(
        context: Context,
        initial: String,
        title: String = "编辑消息",
        onDone: (String) -> Unit
    ) {
        val edit = EditText(context).apply {
            setText(initial)
            textSize = 14f
            setSelection(text?.length ?: 0)
        }
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(wrapper)
            .setNegativeButton("取消", null)
            .setPositiveButton("发送") { _, _ ->
                val value = edit.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) onDone(value)
            }
            .show()
    }
}
