package tests;

import com.neonhud.app.core.engine.Attachment;
import com.neonhud.app.core.skill.SkillKind;
import com.neonhud.app.core.skill.SkillPlanner;
import com.neonhud.app.core.skill.SkillRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class SkillTests {
    static void run() {
        T.section("skills: registry and deterministic planning");
        SkillRegistry r = new SkillRegistry();
        T.eq(9, r.all().size(), "current skill registry has nine planned skills");
        T.check(r.get(SkillKind.IMAGE_READER) != null, "image reader registered");
        T.check(r.get(SkillKind.PDF_READER) != null, "pdf reader registered");
        T.check(r.get(SkillKind.PDF_CREATOR) != null, "pdf creator registered");
        T.check(r.get(SkillKind.AUDIO_READER) != null, "audio reader registered");
        T.check(r.get(SkillKind.VIDEO_READER) != null, "video reader registered");
        T.check(r.get(SkillKind.PROJECT_ZIP_READER) != null, "project zip reader registered");
        List<Attachment> files = Arrays.asList(
                new Attachment(Attachment.Kind.IMAGE, "a.jpg", 1, "u1"),
                new Attachment(Attachment.Kind.PDF, "b.pdf", 2, "u2"),
                new Attachment(Attachment.Kind.AUDIO, "c.mp3", 3, "u3"),
                new Attachment(Attachment.Kind.VIDEO, "c.mp4", 3, "u3"),
                new Attachment(Attachment.Kind.ZIP, "d.zip", 4, "u4"));
        List<SkillKind> plan = SkillPlanner.forTurn("pdf banao aur in files ko dekho", files);
        T.check(plan.contains(SkillKind.PDF_CREATOR), "pdf creation intent selected");
        T.check(plan.contains(SkillKind.IMAGE_READER), "image intent selected from attachment kind");
        T.check(plan.contains(SkillKind.PDF_READER), "pdf reader selected from attachment kind");
        T.check(plan.contains(SkillKind.AUDIO_READER), "audio reader selected from attachment kind");
        T.check(plan.contains(SkillKind.VIDEO_READER), "video reader selected from attachment kind");
        T.check(plan.contains(SkillKind.PROJECT_ZIP_READER), "zip reader selected from attachment kind");
        T.eq(6, plan.size(), "each selected skill appears once");
        List<Attachment> office = Arrays.asList(
                new Attachment(Attachment.Kind.DOCX, "a.docx", 1, "u1"),
                new Attachment(Attachment.Kind.XLSX, "b.xlsx", 2, "u2"),
                new Attachment(Attachment.Kind.PPTX, "c.pptx", 3, "u3"));
        List<SkillKind> officePlan = SkillPlanner.forTurn("in files ko padho", office);
        T.check(officePlan.contains(SkillKind.DOCX), "Word attachment selects Word skill");
        T.check(officePlan.contains(SkillKind.XLSX), "Excel attachment selects Excel skill");
        T.check(officePlan.contains(SkillKind.PPTX), "PowerPoint attachment selects PowerPoint skill");
        T.eq(3, officePlan.size(), "office skills selected once");
        T.check(SkillPlanner.forTurn("Excel banao", Collections.<Attachment>emptyList()).contains(SkillKind.XLSX), "Excel creation intent selects Excel skill");
        T.check(SkillPlanner.forTurn("Word document banao", Collections.<Attachment>emptyList()).contains(SkillKind.DOCX), "Word creation intent selects Word skill");
        T.check(SkillPlanner.forTurn("PowerPoint banao", Collections.<Attachment>emptyList()).contains(SkillKind.PPTX), "PowerPoint creation intent selects PowerPoint skill");
        T.check(SkillPlanner.forTurn("audio file padho", Collections.<Attachment>emptyList()).contains(SkillKind.AUDIO_READER), "audio intent selects audio skill");
    }
}
