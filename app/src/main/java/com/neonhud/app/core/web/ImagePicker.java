package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Chooses the pictures worth showing: real http(s) photos that match the question, no duplicates, no icons, no svg / data: tricks. */
public final class ImagePicker {
    private ImagePicker() { }

    public static List<WebPic> pick(List<WebApi.Image> images, String query, int max) {
        Set<String> q = LinkPicker.tokens(query);
        List<WebPic> out = new ArrayList<WebPic>();
        List<double[]> scores = new ArrayList<double[]>();
        Set<String> seen = new HashSet<String>();
        for (WebApi.Image im : images) {
            String url = im.url == null ? "" : im.url.trim();
            if (!UrlTools.isSafe(url)) continue;
            String low = url.toLowerCase(Locale.ROOT);
            String path = UrlTools.path(low);
            if (path.endsWith(".svg") || path.endsWith(".ico") || path.endsWith(".gif") && path.contains("sprite")) continue;
            if (low.contains("favicon") || low.contains("/icon") || low.contains("sprite") || low.contains("pixel.gif") || low.contains("1x1") || low.contains("spacer") || low.contains("/logo-small")) continue;
            if (!seen.add(UrlTools.key(url))) continue;
            String desc = ResultCleaner.clean(im.description, 120).text;
            Set<String> d = LinkPicker.tokens(desc + " " + path.replace('/', ' ').replace('-', ' ').replace('_', ' '));
            int hit = 0;
            for (String w : q) if (d.contains(w)) hit++;
            double score = (double) hit / Math.max(1, q.size()) + (desc.isEmpty() ? 0 : 0.15) + (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") ? 0.1 : 0);
            // insert sorted (stable for equal scores)
            int pos = out.size();
            while (pos > 0 && scores.get(pos - 1)[0] < score) pos--;
            out.add(pos, new WebPic(url, desc));
            scores.add(pos, new double[]{score});
        }
        return out.size() > max ? new ArrayList<WebPic>(out.subList(0, max)) : out;
    }
}
