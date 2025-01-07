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
package org.openhab.binding.zoneminder.internal.dto;

import com.google.gson.annotations.SerializedName;

/**
 * The {@link AbstractResponseDTO} represents the common exception object included in
 * all responses.
 *
 * @author Mark Hilbush - Initial contribution
 */
public abstract class AbstractResponseDTO {

    /**
     * Common exception object used to convey errors from the API
     */
    @SerializedName("exception")
    public ExceptionDTO exception;
}
