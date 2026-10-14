package com.falsepattern.falsetweaks.modules.threadedupdates.neodymium;

/** Direct access to OptiFine's list without reflective calls for every dirty section. */
public interface ICompactUpdateList {
    Object ft$get(int index);
    Object ft$set(int index, Object value);
    int ft$size();
    void ft$add(int index, Object value);
    Object ft$remove(int index);
    void ft$clear();
}
