package com.neonhud.app.core.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Final prompt budget for attachment reads. Applied after the real reader has loaded a file and before the model sees it.
 * The limits are deliberately conservative for a small on-device model.
 */
public final class ReadBudget {
    public static final int MAX_TOTAL_TEXT_CHARS = 24000;
    public static final int MAX_FILE_TEXT_CHARS = 8000;
    public static final int MAX_TOTAL_IMAGES = 4;
    public static final int MAX_FILE_IMAGES = 4;

    private ReadBudget() { }

    public static List<Attachment> apply(List<Attachment> loaded) {
        if (loaded == null || loaded.isEmpty()) return Collections.emptyList();
        List<Attachment> out = new ArrayList<Attachment>();
        int textRemaining = MAX_TOTAL_TEXT_CHARS;
        int imagesRemaining = MAX_TOTAL_IMAGES;
        for (Attachment a : loaded) {
            if (a == null) continue;
            if (textRemaining <= 0 && imagesRemaining <= 0) break;

            String original = a.text == null ? "" : a.text;
            int perFile = Math.min(MAX_FILE_TEXT_CHARS, Math.max(0, textRemaining));
            String text = fitText(original, perFile);
            int consumedChars = Math.min(original.length(), perFile);
            textRemaining -= consumedChars;

            int take = Math.min(MAX_FILE_IMAGES, Math.max(0, imagesRemaining));
            List<byte[]> imgs = a.images == null || a.images.isEmpty()
                    ? Collections.<byte[]>emptyList()
                    : new ArrayList<byte[]>(a.images.subList(0, Math.min(take, a.images.size())));
            int imgStart = Math.min(a.images == null ? 0 : a.images.size(), take);
            imagesRemaining -= imgStart;

            List<String> labels = new ArrayList<String>();
            for (int i = 0; i < imgs.size(); i++) {
                String label = a.imageLabels != null && i < a.imageLabels.size() ? a.imageLabels.get(i) : "image " + (i + 1);
                labels.add(label);
            }
            out.add(a.loaded(text, imgs, labels));
        }
        return out;
    }

    private static String fitText(String text, int max) {
        if (text == null || text.isEmpty() || max <= 0) return "";
        if (text.length() <= max) return text;
        final String notice = "[content clipped by read budget]";
        if (max <= notice.length()) return notice.substring(0, max);
        int contentCap = max - notice.length() - 1; // one newline before the notice
        String content = clip(text, Math.max(0, contentCap));
        return content.trim() + "\n" + notice;
    }

    private static String clip(String text, int max) {
        if (max <= 0) return "";
        if (text.length() <= max) return text;
        int cut = max;
        int nl = text.lastIndexOf('\n', max);
        if (nl >= Math.max(1, max - 300)) cut = nl;
        return text.substring(0, cut).trim();
    }

}
