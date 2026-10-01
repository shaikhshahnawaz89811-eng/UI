package tests;

import com.neonhud.app.core.engine.AttachmentSendGate;

/** Stage 4 UI-state model: no message may cross the send gate while an attachment is still validating. */
final class AttachmentSendGateTests {
    static void run() {
        T.section("STAGE 4: attachment/send race gate");
        AttachmentSendGate g = new AttachmentSendGate();
        T.check(g.canSend(false, true, false, true), "typed text can send when idle");
        g.beginValidation();
        T.check(g.isBusy(), "attachment validation marks gate busy");
        T.check(!g.canSend(false, true, false, true), "typed text cannot send while attachment is still loading");
        T.check(!g.canSend(false, true, true, true), "existing pending files cannot bypass busy gate");
        g.beginValidation();
        g.endValidation();
        T.check(g.isBusy(), "overlapping attachment checks do not reopen send early");
        g.endValidation();
        T.check(!g.isBusy(), "send gate reopens only after final attachment check finishes");
        T.check(g.canSend(false, true, false, true), "typed text can send after attachment validation completes");
        T.check(!g.canSend(true, true, true, true), "generation state still blocks send");
        T.check(!g.canSend(false, true, true, false), "missing send handler blocks send");
        g.endValidation();
        T.check(!g.isBusy(), "extra endValidation cannot create a bad negative state");
    }
}
