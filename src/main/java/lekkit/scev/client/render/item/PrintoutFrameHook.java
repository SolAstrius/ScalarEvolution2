/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.client.render.item;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderItemInFrameEvent;

/**
 * Java-side wrapper for the {@link RenderItemInFrameEvent} listener
 * that draws printouts on item-frame faces.
 *
 * <p><b>Why Java, not Kotlin.</b> NeoForge's bus implementation
 * reflects on a Consumer's {@code accept} method to discover the
 * event type at registration time. Java lambdas / method references
 * compile to a synthetic class with a properly typed bridge method
 * that exposes the parameter type. Kotlin lambdas compile
 * differently and frequently end up exposing only
 * {@code accept(Object)}, so the bus registers the listener for
 * "anything" or for a wrong concrete type, and the event silently
 * never reaches it. {@link SubscribeEvent} on a real Java method is
 * the canonical, ABI-stable way to subscribe; the bus registers
 * against the method's declared parameter type.
 *
 * <p>Registered from {@code ScevClient.onClientSetup} via
 * {@code NeoForge.EVENT_BUS.register(PrintoutFrameHook.class)}.
 */
public final class PrintoutFrameHook {
    private PrintoutFrameHook() {}

    @SubscribeEvent
    public static void onRenderItemInFrame(RenderItemInFrameEvent event) {
        PrintoutItemRenderer.Companion.onRenderInFrame(event);
    }
}
