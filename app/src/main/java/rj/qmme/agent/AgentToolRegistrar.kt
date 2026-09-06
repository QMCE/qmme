package rj.qmme.agent

import android.content.Context
import rj.qmme.agent.kernel.ApproveFriendTool
import rj.qmme.agent.kernel.ApproveGroupNoticeTool
import rj.qmme.agent.kernel.GetGroupInfoTool
import rj.qmme.agent.kernel.KickGroupMemberTool
import rj.qmme.agent.kernel.ListGroupsTool
import rj.qmme.agent.kernel.ListSessionsTool
import rj.qmme.agent.kernel.MarkReadTool
import rj.qmme.agent.kernel.PublishGroupBulletinTool
import rj.qmme.agent.kernel.ReadMessagesTool
import rj.qmme.agent.kernel.RecallMessageTool
import rj.qmme.agent.kernel.SendMessageTool
import rj.qmme.agent.kernel.SetChatMutedTool
import rj.qmme.agent.kernel.SetChatTopTool
import rj.qmme.agent.kernel.SetGroupAllMutedTool

/**
 * Registers all kernel tools into [KernelToolRegistry].
 * Called from AgentSubsystem.ensure / setEnabled; rebuilds when send_packet toggles.
 */
object AgentToolRegistrar {

    fun ensure(context: Context? = null) {
        synchronized(this) {
            if (KernelToolRegistry.all().isNotEmpty()) return
            KernelToolRegistry.clear()
            KernelToolRegistry.register(ListSessionsTool())
            KernelToolRegistry.register(ReadMessagesTool())
            KernelToolRegistry.register(SendMessageTool())
            KernelToolRegistry.register(RecallMessageTool())
            KernelToolRegistry.register(MarkReadTool())
            KernelToolRegistry.register(ListGroupsTool())
            KernelToolRegistry.register(GetGroupInfoTool())
            KernelToolRegistry.register(SetGroupAllMutedTool())
            KernelToolRegistry.register(KickGroupMemberTool())
            KernelToolRegistry.register(PublishGroupBulletinTool())
            KernelToolRegistry.register(ApproveFriendTool())
            KernelToolRegistry.register(ApproveGroupNoticeTool())
            KernelToolRegistry.register(SetChatTopTool())
            KernelToolRegistry.register(SetChatMutedTool())
            KernelToolRegistry.register(EventMonitorTool())
            KernelToolRegistry.register(TimerTool())
        }
    }
}
