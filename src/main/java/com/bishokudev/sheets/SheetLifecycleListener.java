package com.bishokudev.sheets;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.confluence.event.events.content.page.PageCopyEvent;
import com.atlassian.confluence.event.events.content.page.PageRemoveEvent;
import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/** Copies sheets when a page is copied. Removal retains data until safe post-commit cleanup is available. */
public final class SheetLifecycleListener implements InitializingBean, DisposableBean {
    private static final Logger LOG = LogManager.getLogger(SheetLifecycleListener.class);
    private final EventPublisher eventPublisher;
    private final SheetStore store;

    public SheetLifecycleListener(EventPublisher eventPublisher, ActiveObjects ao, ClusterLockService locks) {
        this.eventPublisher = eventPublisher;
        this.store = new SheetStore(ao, locks);
    }

    @Override
    public void afterPropertiesSet() {
        if (eventPublisher != null) {
            eventPublisher.register(this);
        }
    }

    @Override
    public void destroy() {
        if (eventPublisher != null) {
            eventPublisher.unregister(this);
        }
    }

    @EventListener
    public void onPageRemove(PageRemoveEvent event) {
        if (event == null || event.getPage() == null) return;
        long pageId = event.getPage().getId();
        // Page removal events may run before Confluence's transaction commits.
        // AO has a separate transaction: deleting here could destroy sheets even
        // when page removal rolls back. Keep data until a durable post-commit
        // retention job can establish that the page is permanently absent.
        LOG.info("Retaining sheet history for removed page {} pending safe retention cleanup", pageId);
    }

    @EventListener
    public void onPageCopy(PageCopyEvent event) {
        if (event == null || event.getOrigin() == null || event.getDestination() == null) return;
        long sourcePageId = event.getOrigin().getId();
        long targetPageId = event.getDestination().getId();
        try {
            int copied = store.copyPageSheets(sourcePageId, targetPageId, "system");
            if (copied > 0) {
                LOG.info("Copied {} sheets from page {} to page {}", copied, sourcePageId, targetPageId);
            }
        } catch (Exception e) {
            LOG.warn("Failed to copy sheets from page {} to page {}", sourcePageId, targetPageId, e);
        }
    }
}

