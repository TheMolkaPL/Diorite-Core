package org.diorite.impl.entity;

import org.diorite.impl.ServerImpl;
import org.diorite.ImmutableLocation;
import org.diorite.entity.MinecartRideable;

public abstract class MinecartRideableImpl extends MinecartImpl implements MinecartRideable
{
    public MinecartRideableImpl(final ServerImpl server, final int id, final ImmutableLocation location)
    {
        super(server, id, location);
    }
}
