/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.boschshc.internal.services.bypass;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.boschshc.internal.services.BoschSHCService;
import org.openhab.binding.boschshc.internal.services.bypass.dto.BypassServiceState;

/**
 * Service for the bypass state of devices such as the Door/Window Contact II
 * 
 * @author David Pace - Initial contribution
 *
 */
@NonNullByDefault
public class BypassService extends BoschSHCService<BypassServiceState> {

    public BypassService() {
        super("Bypass", BypassServiceState.class);
    }
}
