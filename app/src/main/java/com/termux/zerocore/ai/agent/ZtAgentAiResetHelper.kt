package com.termux.zerocore.ai.agent

import android.content.Context
import com.example.xh_lib.utils.UUtils
import com.termux.R
import com.termux.zerocore.dialog.SwitchDialog
import com.termux.zerocore.ftp.utils.UserSetManage

object ZtAgentAiResetHelper {

    private var uiRefreshCallback: (() -> Unit)? = null

    fun registerUiRefreshCallback(callback: (() -> Unit)?) {
        uiRefreshCallback = callback
    }

    fun showResetConfirmDialog(context: Context, onReset: (() -> Unit)? = null) {
        val dialog = SwitchDialog(context)
        dialog.createSwitchDialog(context.getString(R.string.zt_agent_ai_reset_dialog_message))
        dialog.title?.text = context.getString(R.string.zt_agent_ai_reset_dialog_title)
        dialog.cancel?.setOnClickListener { dialog.dismiss() }
        dialog.ok?.setOnClickListener {
            resetAllData()
            UUtils.showMsg(context.getString(R.string.zt_agent_ai_reset_done))
            onReset?.invoke()
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 清空聊天记录、自定义系统提示词、服务商缓存等智能体运行时数据（保留 API 配置与开关） */
    fun resetAllData() {
        val bean = UserSetManage.get().getZTUserBean()
        bean.agentAiChatHistoryJson = null
        bean.agentAiSystemPrompt = ""
        bean.agentAiProviderCacheJson = null
        UserSetManage.get().setZTUserBean(bean)
        // 会话重建后滑动窗口里的旧记录已无意义，一并清空，避免误判为新会话的循环。
        ZtAgentLoopGuard.default.reset()
        uiRefreshCallback?.invoke()
    }
}
