package io.stategraph.example.item;

/** States of an Item, from pending through packing to a terminal packed or cancelled outcome. */
public enum ItemStatus { PENDING, PACKING, PACKED, CANCELLED }
