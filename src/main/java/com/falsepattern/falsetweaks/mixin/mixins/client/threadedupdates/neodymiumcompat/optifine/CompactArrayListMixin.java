package com.falsepattern.falsetweaks.mixin.mixins.client.threadedupdates.neodymiumcompat.optifine;

import com.falsepattern.falsetweaks.modules.threadedupdates.neodymium.ICompactUpdateList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "CompactArrayList", remap = false)
public abstract class CompactArrayListMixin implements ICompactUpdateList {
    @Invoker("get") public abstract Object ft$get(int index);
    @Invoker("set") public abstract Object ft$set(int index, Object value);
    @Invoker("size") public abstract int ft$size();
    @Invoker("add") public abstract void ft$add(int index, Object value);
    @Invoker("remove") public abstract Object ft$remove(int index);
    @Invoker("clear") public abstract void ft$clear();
}
