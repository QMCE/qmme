package rj.qmme.data.emotion;

import com.tencent.mobileqq.ptt.IQQRecorder;
import com.tencent.qqnt.watch.ptt.PttRecordCallback;

/** Java accessors for obfuscated public fields that Kotlin metadata marks private. */
public final class EmotionSdkAccess {
    private EmotionSdkAccess() {}

    public static void setPttRecordPanel(
            PttRecordCallback callback,
            IQQRecorder.OnQQRecorderListener panel
    ) {
        callback.c = panel;
    }
}
