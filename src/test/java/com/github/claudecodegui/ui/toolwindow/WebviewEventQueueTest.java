package com.github.claudecodegui.ui.toolwindow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebviewEventQueueTest {

    @Test
    public void mergesAdjacentContentDeltasIntoOneOrderedCall() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("onContentDelta", "a");
        queue.enqueue("onContentDelta", "b");

        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("window.onContentDelta('ab')"));
        queue.dispose();
    }

    @Test
    public void preservesLifecycleOrderInsteadOfCollapsingStateAcrossBoundaries() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("showLoading", "true");
        queue.enqueue("onStreamStart");
        queue.enqueue("showLoading", "false");
        scheduled.remove(0).run();

        String script = scripts.get(0);
        assertTrue(script.indexOf("window.showLoading('true')")
                < script.indexOf("window.onStreamStart()"));
        assertTrue(script.indexOf("window.onStreamStart()")
                < script.indexOf("window.showLoading('false')"));
        queue.dispose();
    }

    @Test
    public void separatesSnapshotFromStreamStartSoStartCannotCancelIt() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = newQueue(browser, disposed, scheduled, scripts);

        queue.enqueue("updateMessages", "snapshot", "1");
        queue.enqueue("onStreamStart");
        scheduled.remove(0).run();
        scheduled.remove(0).run();

        assertEquals(2, scripts.size());
        assertTrue(scripts.get(0).contains("window.updateMessages('snapshot', '1')"));
        assertTrue(scripts.get(1).contains("window.onStreamStart()"));
        queue.dispose();
    }

    @Test
    public void dropsQueuedEventsWhenPageGenerationChanges() {
        AtomicReference<Object> browser = new AtomicReference<>(new Object());
        AtomicBoolean disposed = new AtomicBoolean();
        AtomicInteger pageGeneration = new AtomicInteger(1);
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = new WebviewEventQueue<>(
                browser::get,
                disposed::get,
                pageGeneration::get,
                scheduled::add,
                (ignoredBrowser, ignoredPageGeneration, script) -> scripts.add(script));

        queue.enqueueRaw("window.showAskUserQuestionDialog('stale')");
        pageGeneration.set(2);
        queue.pageChanged();
        scheduled.remove(0).run();

        assertTrue("events from the previous page must not execute", scripts.isEmpty());
        queue.dispose();
    }

    @Test
    public void delayedOldPageEnqueueDoesNotEraseNewPageEvents() throws Exception {
        Object browser = new Object();
        AtomicInteger pageGeneration = new AtomicInteger(1);
        List<Runnable> scheduled = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        WebviewEventQueue<Object> queue = new WebviewEventQueue<>(
                () -> browser, () -> false, pageGeneration::get, scheduled::add,
                (ignoredBrowser, ignoredPage, script) -> scripts.add(script));
        WebviewEventQueue.JsCall<Object> delayedCall =
                new WebviewEventQueue.JsCall<>(browser, 1, null, new String[0], "oldPage()");
        pageGeneration.set(2);
        queue.pageChanged();
        queue.enqueueRaw("newPage()");

        // Simulate an old sender waiting for the lock while a new-page event is queued.
        java.lang.reflect.Method enqueue = WebviewEventQueue.class.getDeclaredMethod("enqueue", WebviewEventQueue.JsCall.class);
        enqueue.setAccessible(true);
        enqueue.invoke(queue, delayedCall);
        scheduled.remove(0).run();

        assertEquals(1, scripts.size());
        assertTrue(scripts.get(0).contains("newPage()"));
        org.junit.Assert.assertFalse(scripts.get(0).contains("oldPage()"));
    }

    private static WebviewEventQueue<Object> newQueue(
            AtomicReference<Object> browser,
            AtomicBoolean disposed,
            List<Runnable> scheduled,
            List<String> scripts
    ) {
        return new WebviewEventQueue<>(
                browser::get,
                disposed::get,
                () -> 0,
                scheduled::add,
                (ignoredBrowser, ignoredPageGeneration, script) -> scripts.add(script)
        );
    }
}
