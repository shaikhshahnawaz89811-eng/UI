package com.neonhud.app.core.engine;

/** Optional, explicit selection for a read task. Empty means read normally within the budget. */
public final class ReadSelection {
    public final int pageStart;
    public final int pageEnd;
    public final int slideStart;
    public final int slideEnd;
    public final String sheet;

    private ReadSelection(int pageStart, int pageEnd, int slideStart, int slideEnd, String sheet) {
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.slideStart = slideStart;
        this.slideEnd = slideEnd;
        this.sheet = sheet == null ? "" : sheet.trim();
    }

    public static ReadSelection none() { return new ReadSelection(0, 0, 0, 0, ""); }

    public static ReadSelection pages(int start, int end) {
        if (start < 1 || end < start) throw new IllegalArgumentException("invalid page selection");
        return new ReadSelection(start, end, 0, 0, "");
    }

    public static ReadSelection slides(int start, int end) {
        if (start < 1 || end < start) throw new IllegalArgumentException("invalid slide selection");
        return new ReadSelection(0, 0, start, end, "");
    }

    public static ReadSelection sheet(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("sheet name is required");
        return new ReadSelection(0, 0, 0, 0, name);
    }

    public boolean hasPages() { return pageStart > 0; }
    public boolean hasSlides() { return slideStart > 0; }
    public boolean hasSheet() { return !sheet.isEmpty(); }
    public boolean isEmpty() { return !hasPages() && !hasSlides() && !hasSheet(); }

    public String signature() {
        if (hasPages()) return "page:" + pageStart + "-" + pageEnd;
        if (hasSlides()) return "slide:" + slideStart + "-" + slideEnd;
        if (hasSheet()) return "sheet:" + sheet.toLowerCase(java.util.Locale.ROOT);
        return "all";
    }

    @Override public String toString() { return signature(); }
}
