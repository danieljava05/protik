package com.protik.video;

import java.util.List;

/**
 * The renditions the platform offers, cheapest first. Other modules use it to tell users what watching will cost
 * and to validate usage reports, without reading the video schema (ADR 0018).
 */
public interface RenditionCatalog {

    /** @return every rendition ordered by maximum bitrate, lowest first (audio only, then 144p, ...) */
    List<Rendition> all();
}
