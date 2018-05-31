/*
 * Copyright (c) 2018 Kevin Herron
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *   http://www.eclipse.org/org/documents/edl-v10.html.
 */

package org.eclipse.milo.opcua.sdk.server.events.conversions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;

final class UInt64Conversions {

    private UInt64Conversions() {}

    @Nonnull
    static Boolean uInt64ToBoolean(@Nonnull ULong ul) {
        return ul.intValue() != 0;
    }

    @Nullable
    static UByte uInt64ToByte(@Nonnull ULong ul) {
        long l = ul.longValue();

        if (Long.compareUnsigned(l, UByte.MAX_VALUE) <= 0) {
            return ubyte(l);
        } else {
            return null;
        }
    }

    @Nullable
    static Double uInt64ToDouble(@Nonnull ULong ul) {
        long l = ul.longValue();

        if (Long.compareUnsigned(l, (long) Double.MAX_VALUE) <= 0) {
            return ul.doubleValue();
        } else {
            return null;
        }
    }

    @Nullable
    static Float uInt64ToFloat(@Nonnull ULong ul) {
        long l = ul.longValue();

        if (Long.compareUnsigned(l, (long) Float.MAX_VALUE) <= 0) {
            return ul.floatValue();
        } else {
            return null;
        }
    }

    @Nullable
    static Short uInt64ToInt16(@Nonnull ULong ul) {
        long l = ul.longValue();

        if (Long.compareUnsigned(l, Short.MAX_VALUE) <= 0) {
            return (short) l;
        } else {
            return null;
        }
    }

    @Nullable
    static Integer uInt64ToInt32(@Nonnull ULong ul) {
        long l = ul.longValue();

        if (Long.compareUnsigned(l, Integer.MAX_VALUE) <= 0) {
            return (int) l;
        } else {
            return null;
        }
    }

    @Nullable
    static Long uInt64ToInt64(@Nonnull ULong ul) {
        long l = ul.longValue();

        if (Long.compareUnsigned(l, Long.MAX_VALUE) <= 0) {
            return l;
        } else {
            return null;
        }
    }

    @Nullable
    static Byte uInt64ToSByte(@Nonnull ULong ul) {
        long l = ul.longValue();

        if (Long.compareUnsigned(l, Byte.MAX_VALUE) <= 0) {
            return (byte) l;
        } else {
            return null;
        }
    }

    @Nullable
    static StatusCode uInt64ToStatusCode(@Nonnull ULong ul) {
        UInteger ui = uInt64ToUInt32(ul);

        return ui != null ? UInt32Conversions.uInt32ToStatusCode(ui) : null;
    }

    @Nonnull
    static String uInt64ToString(ULong ul) {
        return ul.toString();
    }

    @Nullable
    static UShort uInt64ToUInt16(ULong ul) {
        long l = ul.longValue();

        if (Long.compareUnsigned(l, UShort.MAX_VALUE) <= 0) {
            return ushort((int) l);
        } else {
            return null;
        }
    }

    @Nullable
    static UInteger uInt64ToUInt32(ULong ul) {
        long l = ul.longValue();

        if (Long.compareUnsigned(l, UInteger.MAX_VALUE) <= 0) {
            return uint(l);
        } else {
            return null;
        }
    }

}