package com.protik.video.processing;

import com.protik.video.persistence.VideoRepository;
import com.protik.video.storage.ObjectStorage;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the stored files of deleted assets (ADR 0022 decision 6). A trigger records every deleted asset's key, including
 * those removed by a cascade, so deleting a video or an account cannot leave files behind. A key whose object is already
 * gone is simply forgotten; a key that cannot be deleted stays for the next round.
 */
@Component
class TombstoneSweeper {

    static final int BATCH = 200;
    private static final Logger log = LoggerFactory.getLogger(TombstoneSweeper.class);

    private final VideoRepository videos;
    private final ObjectStorage storage;
    private final TransactionTemplate transaction;

    TombstoneSweeper(VideoRepository videos, ObjectStorage storage, PlatformTransactionManager transactions) {
        this.videos = videos;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${protik.storage.sweep-interval:PT1M}", initialDelayString = "${protik.storage.sweep-interval:PT1M}")
    void sweepScheduled() {
        sweep();
    }

    /** @return how many keys were cleared */
    int sweep() {
        int cleared = 0;
        for (String key : transaction.execute(s -> videos.tombstones(BATCH))) {
            try {
                storage.delete(key);
                transaction.executeWithoutResult(s -> videos.deleteTombstone(key));
                cleared++;
            } catch (IOException | RuntimeException e) {
                log.warn("Could not delete stored file {}; will retry: {}", key, e.toString());
            }
        }
        return cleared;
    }
}
