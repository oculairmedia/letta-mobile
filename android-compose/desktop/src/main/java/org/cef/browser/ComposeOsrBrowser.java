// Lives in org.cef.browser because CefBrowser_N (the JNI-backed browser base
// class) and its lifecycle hooks are package-private in upstream java-cef.
// This is the same technique JetBrains' jcef fork uses internally for its
// CefBrowserOsrWithHandler; upstream never merged an embedder-facing
// render-handler API, so a split package on the classpath is the supported
// escape hatch (jcefmaven jars are not sealed).
package org.cef.browser;

import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Off-screen CEF browser that hands BGRA frames to an embedder callback
 * instead of painting a jogl {@code GLCanvas} (upstream {@code CefBrowserOsr}).
 * A heavyweight AWT/GL canvas cannot composite into a per-pixel transparent
 * Compose window, so the pet-mode host draws these frames itself; skipping
 * the GL path also means jogamp natives never load.
 *
 * <p>Threading: {@link #onPaint} arrives on a CEF thread. The consumer must
 * copy the buffer before returning — CEF reuses it for the next frame.
 */
public class ComposeOsrBrowser extends CefBrowser_N implements CefRenderHandler {
    /** Receives raw BGRA frames sized {@code width * height * 4} bytes. */
    public interface FrameConsumer {
        void onFrame(ByteBuffer bgraBuffer, int width, int height);
    }

    private final FrameConsumer frameConsumer;
    private final boolean transparent;
    private volatile Rectangle viewRect = new Rectangle(0, 0, 1, 1);
    private volatile Point screenOrigin = new Point(0, 0);
    private volatile double scaleFactor = 1.0;
    private final CopyOnWriteArrayList<Consumer<CefPaintEvent>> paintListeners =
            new CopyOnWriteArrayList<>();

    public ComposeOsrBrowser(
            CefClient client, String url, boolean transparent, FrameConsumer frameConsumer) {
        super(client, url, null, null, null, null);
        this.transparent = transparent;
        this.frameConsumer = frameConsumer;
    }

    /**
     * Publish the embedder-side viewport: logical (DIP) size, the window's
     * device scale, and its screen position. CEF renders frames at
     * {@code size * scale} pixels. Safe to call from any thread.
     */
    public void updateViewport(int width, int height, double scale, int screenX, int screenY) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        Rectangle current = viewRect;
        boolean changed = w != current.width || h != current.height || scale != scaleFactor;
        viewRect = new Rectangle(0, 0, w, h);
        screenOrigin = new Point(screenX, screenY);
        scaleFactor = scale;
        if (changed) wasResized(w, h);
    }

    @Override
    public void createImmediately() {
        // Windowless: no parent window handle, no AWT realization required.
        createBrowser(getClient(), 0, getUrl(), true, transparent, null, getRequestContext());
    }

    @Override
    public Component getUIComponent() {
        return null; // frames are consumed via FrameConsumer, not an AWT tree
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url,
            CefRequestContext context, CefBrowser_N parent, Point inspectAt) {
        return null; // devtools not supported in the pet host
    }

    // -- CefRenderHandler -----------------------------------------------------

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return viewRect;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        Point point = new Point(screenOrigin);
        point.translate(viewPoint.x, viewPoint.y);
        return point;
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        Rectangle bounds = viewRect.getBounds();
        screenInfo.Set(scaleFactor, 32, 8, false, bounds, bounds);
        return true;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        // The pet page renders no CEF popups (no <select>, no context menus).
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {}

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
            ByteBuffer buffer, int width, int height) {
        if (popup) return;
        frameConsumer.onFrame(buffer, width, height);
        if (!paintListeners.isEmpty()) {
            CefPaintEvent event =
                    new CefPaintEvent(browser, popup, dirtyRects, buffer, width, height);
            for (Consumer<CefPaintEvent> listener : paintListeners) {
                listener.accept(event);
            }
        }
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return true; // cursor is owned by the Compose window, not the page
    }

    @Override
    public boolean startDragging(
            CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        return false; // no HTML drag sources in the pet page
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {}

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
        paintListeners.add(listener);
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
        paintListeners.clear();
        paintListeners.add(listener);
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
        paintListeners.remove(listener);
    }

    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        CompletableFuture<BufferedImage> future = new CompletableFuture<>();
        future.completeExceptionally(new UnsupportedOperationException(
                "Screenshots go through the avatar wire protocol (captureThumbnail)"));
        return future;
    }
}
