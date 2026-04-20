/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.main;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ScevDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ScalarEvolution.MODID);

    /**
     * Persistent disk image UUID, attached to flash / HDD / NVMe items.
     * Each UUID points at an image file under {@code ./scev/images/<uuid>.img}.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> STORAGE_UUID =
            DATA_COMPONENTS.registerComponentType("storage_uuid", b -> b
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC));

    /**
     * Components installed into a motherboard item: CPU, flash, RAM, NVMe, PCI cards.
     * Up to 14 slots, laid out per {@link lekkit.scev.items.MotherboardItem} constants.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> MOTHERBOARD_INVENTORY =
            DATA_COMPONENTS.registerComponentType("motherboard_inventory", b -> b
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC));

    public static void register(IEventBus modBus) {
        DATA_COMPONENTS.register(modBus);
    }

    private ScevDataComponents() {}
}
