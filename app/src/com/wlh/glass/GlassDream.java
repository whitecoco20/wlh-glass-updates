package com.wlh.glass;

import android.service.dreams.DreamService;

/** WLH Glass screensaver: your background, a large clock and the weather. */
public class GlassDream extends DreamService {
    private GlassView view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        view = new GlassView(this);
        view.dreamMode = true;
        setContentView(view);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); if (view != null) view.onShown(); }
    @Override public void onDreamingStopped() { if (view != null) view.onHidden(); super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() { if (view != null) view.release(); super.onDetachedFromWindow(); }
}
