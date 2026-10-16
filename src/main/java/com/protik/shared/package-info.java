/**
 * Shared kernel: job queue, error handling, ids. An OPEN module, so other
 * modules may use its internals; it must never depend on another module.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Shared",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.protik.shared;
