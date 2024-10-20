/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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
package org.openhab.binding.modbus.lambda.internal.handler;

import static org.openhab.binding.modbus.lambda.internal.LambdaBindingConstants.*;
import static org.openhab.core.library.unit.SIUnits.CELSIUS;
import static org.openhab.core.library.unit.Units.*;

import java.util.Optional;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.handler.EndpointNotInitializedException;
import org.openhab.binding.modbus.handler.ModbusEndpointThingHandler;
import org.openhab.binding.modbus.lambda.internal.LambdaConfiguration;
import org.openhab.binding.modbus.lambda.internal.dto.AmbientBlock;
import org.openhab.binding.modbus.lambda.internal.dto.Boiler1Block;
import org.openhab.binding.modbus.lambda.internal.dto.Boiler1MtBlock;
import org.openhab.binding.modbus.lambda.internal.dto.Buffer1Block;
import org.openhab.binding.modbus.lambda.internal.dto.Buffer1MtBlock;
import org.openhab.binding.modbus.lambda.internal.dto.EManagerBlock;
import org.openhab.binding.modbus.lambda.internal.dto.HeatingCircuit1Block;
import org.openhab.binding.modbus.lambda.internal.dto.HeatingCircuit1SettingBlock;
import org.openhab.binding.modbus.lambda.internal.dto.Heatpump1Block;
import org.openhab.binding.modbus.lambda.internal.dto.Heatpump1SetBlock;
import org.openhab.binding.modbus.lambda.internal.parser.AmbientBlockParser;
import org.openhab.binding.modbus.lambda.internal.parser.Boiler1BlockParser;
import org.openhab.binding.modbus.lambda.internal.parser.Boiler1MtBlockParser;
import org.openhab.binding.modbus.lambda.internal.parser.Buffer1BlockParser;
import org.openhab.binding.modbus.lambda.internal.parser.Buffer1MtBlockParser;
import org.openhab.binding.modbus.lambda.internal.parser.EManagerBlockParser;
import org.openhab.binding.modbus.lambda.internal.parser.HeatingCircuit1BlockParser;
import org.openhab.binding.modbus.lambda.internal.parser.HeatingCircuit1SettingBlockParser;
import org.openhab.binding.modbus.lambda.internal.parser.Heatpump1BlockParser;
import org.openhab.binding.modbus.lambda.internal.parser.Heatpump1SetBlockParser;
import org.openhab.core.io.transport.modbus.AsyncModbusFailure;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.ModbusWriteRegisterRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.io.transport.modbus.PollTask;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LambdaHandler} is responsible for handling commands,
 * which are sent to one of the channels and for polling the modbus.
 *
 * @author Paul Frank - Initial contribution
 */
@NonNullByDefault
public class LambdaHandler extends BaseThingHandler {

    public abstract class AbstractBasePoller {

        private final Logger logger = LoggerFactory.getLogger(LambdaHandler.class);

        private volatile @Nullable PollTask pollTask;

        public synchronized void unregisterPollTask() {
            PollTask task = pollTask;
            if (task == null) {
                return;
            }

            ModbusCommunicationInterface mycomms = LambdaHandler.this.comms;
            if (mycomms != null) {
                mycomms.unregisterRegularPoll(task);
            }
            pollTask = null;
        }

        /**
         * Register poll task This is where we set up our regular poller
         */
        public synchronized void registerPollTask(int address, int length, ModbusReadFunctionCode readFunctionCode) {
            logger.debug("Setting up regular polling");

            ModbusCommunicationInterface mycomms = LambdaHandler.this.comms;
            LambdaConfiguration myconfig = LambdaHandler.this.config;
            if (myconfig == null || mycomms == null) {
                throw new IllegalStateException("registerPollTask called without proper configuration");
            }

            ModbusReadRequestBlueprint request = new ModbusReadRequestBlueprint(getSlaveId(), readFunctionCode, address,
                    length, myconfig.getMaxTries());

            long refreshMillis = myconfig.getRefreshMillis();

            pollTask = mycomms.registerRegularPoll(request, refreshMillis, 1000, result -> {
                result.getRegisters().ifPresent(this::handlePolledData);
                if (getThing().getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                }
            }, LambdaHandler.this::handleReadError);
        }

        public synchronized void poll() {
            PollTask task = pollTask;
            ModbusCommunicationInterface mycomms = LambdaHandler.this.comms;
            if (task != null && mycomms != null) {
                mycomms.submitOneTimePoll(task.getRequest(), task.getResultCallback(), task.getFailureCallback());
            }
        }

        protected abstract void handlePolledData(ModbusRegisterArray registers);
    }

    /**
     * Logger instance
     */
    private final Logger logger = LoggerFactory.getLogger(LambdaHandler.class);

    /**
     * Configuration instance
     */
    protected @Nullable LambdaConfiguration config = null;
    /**
     * Parser used to convert incoming raw messages into system blocks
     * private final SystemInfromationBlockParser systemInformationBlockParser = new SystemInfromationBlockParser();
     */
    /**
     * Parsers used to convert incoming raw messages into state blocks
     */
    private final AmbientBlockParser ambientBlockParser = new AmbientBlockParser();
    private final EManagerBlockParser emanagerBlockParser = new EManagerBlockParser();
    private final Boiler1BlockParser boiler1BlockParser = new Boiler1BlockParser();
    private final Boiler1MtBlockParser boiler1mtBlockParser = new Boiler1MtBlockParser();
    private final Buffer1BlockParser buffer1BlockParser = new Buffer1BlockParser();
    private final Buffer1MtBlockParser buffer1mtBlockParser = new Buffer1MtBlockParser();
    private final Heatpump1BlockParser heatpump1BlockParser = new Heatpump1BlockParser();
    private final Heatpump1SetBlockParser heatpump1SetBlockParser = new Heatpump1SetBlockParser();
    private final HeatingCircuit1BlockParser heatingcircuit1BlockParser = new HeatingCircuit1BlockParser();
    private final HeatingCircuit1SettingBlockParser heatingcircuit1settingBlockParser = new HeatingCircuit1SettingBlockParser();

    /**
     * These are the tasks used to poll the device
     */
    private volatile @Nullable AbstractBasePoller ambientPoller = null;
    private volatile @Nullable AbstractBasePoller emanagerPoller = null;
    private volatile @Nullable AbstractBasePoller heatpump1Poller = null;
    private volatile @Nullable AbstractBasePoller heatpump1SetPoller = null;
    private volatile @Nullable AbstractBasePoller boiler1Poller = null;
    private volatile @Nullable AbstractBasePoller boiler1mtPoller = null;
    private volatile @Nullable AbstractBasePoller buffer1Poller = null;
    private volatile @Nullable AbstractBasePoller buffer1mtPoller = null;
    private volatile @Nullable AbstractBasePoller heatingcircuit1Poller = null;
    private volatile @Nullable AbstractBasePoller heatingcircuit1settingPoller = null;
    /**
     * Communication interface to the slave endpoint we're connecting to
     */
    protected volatile @Nullable ModbusCommunicationInterface comms = null;
    /**
     * This is the slave id, we store this once initialization is complete
     */
    private volatile int slaveId;

    /**
     * Instances of this handler should get a reference to the modbus manager
     *
     * @param thing the thing to handle
     */
    public LambdaHandler(Thing thing) {
        super(thing);
    }

    /**
     * @param address address of the value to be written on the modbus
     * @param shortValue value to be written on the modbus
     */
    protected void writeInt16(int address, short shortValue) {
        // loggertrace("187 writeInt16: Es wird geschrieben, Adresse: {} Wert: {}", address, shortValue);
        LambdaConfiguration myconfig = LambdaHandler.this.config;
        ModbusCommunicationInterface mycomms = LambdaHandler.this.comms;

        if (myconfig == null || mycomms == null) {
            throw new IllegalStateException("registerPollTask called without proper configuration");
        }
        // big endian byte ordering
        byte hi = (byte) (shortValue >> 8);
        byte lo = (byte) shortValue;
        ModbusRegisterArray data = new ModbusRegisterArray(hi, lo);

        // loggertrace("199 hi: {}, lo: {}", hi, lo);
        ModbusWriteRegisterRequestBlueprint request = new ModbusWriteRegisterRequestBlueprint(slaveId, address, data,
                true, myconfig.getMaxTries());

        mycomms.submitOneTimeWrite(request, result -> {
            if (hasConfigurationError()) {
                return;
            }
            // loggertrace("Successful write, matching request {}", request);
            LambdaHandler.this.updateStatus(ThingStatus.ONLINE);
        }, failure -> {
            LambdaHandler.this.handleWriteError(failure);
            // loggertrace("Unsuccessful write, matching request {}", request);
        });
    }

    /**
     * @param command get the value of this command.
     * @return short the value of the command multiplied by 10 (see datatype 2 in
     *         the stiebel eltron modbus documentation)
     * 
     *         private short getScaled10Int16Value(Command command) throws LambdaException {
     *         if (command instanceof QuantityType quantityCommand) {
     *         QuantityType<?> c = quantityCommand.toUnit(CELSIUS);
     *         if (c != null) {
     *         return (short) (c.doubleValue() * 10);
     *         } else {
     *         throw new LambdaException("Unsupported unit");
     *         }
     *         }
     *         if (command instanceof DecimalType c) {
     *         return (short) (c.doubleValue() * 10);
     *         }
     *         throw new LambdaException("Unsupported command type");
     *         }
     * 
     *         private short getScaled100Int16Value(Command command) throws LambdaException {
     *         if (command instanceof QuantityType quantityCommand) {
     *         QuantityType<?> c = quantityCommand.toUnit(CELSIUS);
     *         if (c != null) {
     *         return (short) (c.doubleValue() * 100);
     *         } else {
     *         throw new LambdaException("Unsupported unit");
     *         }
     *         }
     *         if (command instanceof DecimalType c) {
     *         return (short) (c.doubleValue() * 100);
     *         }
     *         throw new LambdaException("Unsupported command type");
     *         }
     */

    /**
     * @param command get the value of this command.
     * @return short the value of the command as short
     */
    private short getInt16Value(Command command) throws LambdaException {
        if (command instanceof QuantityType quantityCommand) {
            QuantityType<?> c = quantityCommand.toUnit(WATT);
            if (c != null) {
                return c.shortValue();
            } else {
                throw new LambdaException("Unsupported unit");
            }
        }
        if (command instanceof DecimalType c) {
            return c.shortValue();
        }
        throw new LambdaException("Unsupported command type");
    }

    private short getScaledInt16Value(Command command) throws LambdaException {
        if (command instanceof QuantityType quantityCommand) {
            QuantityType<?> c = quantityCommand.toUnit(CELSIUS);
            if (c != null) {
                return (short) (c.doubleValue() * 10);
            } else {
                throw new LambdaException("Unsupported unit");
            }
        }
        if (command instanceof DecimalType c) {
            return (short) (c.doubleValue() * 10);
        }
        throw new LambdaException("Unsupported command type");
    }

    /**
     * Handle incoming commands.
     */
    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // loggertrace("283 handleCommand, channelUID: {} command {} ", channelUID, command);
        if (RefreshType.REFRESH == command) {
            String groupId = channelUID.getGroupId();
            if (groupId != null) {
                AbstractBasePoller poller;
                switch (groupId) {
                    case GROUP_GENERAL_AMBIENT:
                        poller = ambientPoller;
                        break;
                    case GROUP_GENERAL_EMANAGER:
                        poller = emanagerPoller;
                        break;
                    case GROUP_HEATPUMP1:
                        poller = heatpump1Poller;
                        break;
                    case GROUP_HEATPUMP1SET:
                        poller = heatpump1SetPoller;
                        break;
                    case GROUP_BOILER1:
                        poller = boiler1Poller;
                        break;
                    case GROUP_BOILER1MT:
                        poller = boiler1mtPoller;
                        break;
                    case GROUP_BUFFER1:
                        poller = buffer1Poller;
                        break;
                    case GROUP_BUFFER1MT:
                        poller = buffer1mtPoller;
                        break;
                    case GROUP_HEATINGCIRCUIT1:
                        poller = heatingcircuit1Poller;
                        break;
                    case GROUP_HEATINGCIRCUIT1SETTING:
                        poller = heatingcircuit1settingPoller;
                        break;
                    default:
                        poller = null;
                        break;
                }
                if (poller != null) {
                    // loggertrace("336 Es wird gepollt }");
                    poller.poll();
                }
            }
        } else {
            // loggertrace("341 handleCommand: Es wird geschrieben, GroupID: {}, command {}", channelUID.getGroupId(),
            // command);
            try {

                // loggertrace("345 vor EMANAGER");
                if (GROUP_GENERAL_EMANAGER.equals(channelUID.getGroupId())) {

                    // loggertrace("330 im EMANAGER channelUID {} ", channelUID.getIdWithoutGroup());
                    switch (channelUID.getIdWithoutGroup()) {

                        case CHANNEL_ACTUAL_POWER:

                            // loggertrace("336 command: {}", command);
                            writeInt16(102, getInt16Value(command));
                            break;

                    }
                }
                if (GROUP_BOILER1MT.equals(channelUID.getGroupId())) {
                    // loggertrace("345 im BOILER1MT channelUID {} ", channelUID.getIdWithoutGroup());

                    switch (channelUID.getIdWithoutGroup()) {

                        case CHANNEL_BOILER1_MAXIMUM_BOILER_TEMPERATURE:

                            // loggertrace("347 command: {}", command);
                            writeInt16(2050, getScaledInt16Value(command));
                            break;

                    }
                }
                if (GROUP_BUFFER1MT.equals(channelUID.getGroupId())) {
                    // loggertrace("359 im BUFFER1MT channelUID {} ", channelUID.getIdWithoutGroup());

                    switch (channelUID.getIdWithoutGroup()) {

                        case CHANNEL_BUFFER1_MAXIMUM_BOILER_TEMPERATURE:

                            // loggertrace("365 command: {}", command);
                            writeInt16(3050, getScaledInt16Value(command));
                            break;

                    }
                }

                if (GROUP_HEATINGCIRCUIT1.equals(channelUID.getGroupId())) {
                    // loggertrace("387 im HEATINGCIRCUI1 channelUID {} ", channelUID.getIdWithoutGroup());

                    switch (channelUID.getIdWithoutGroup()) {

                        case CHANNEL_HEATINGCIRCUIT1_ROOM_DEVICE_TEMPERATURE:
                            // loggertrace("393 command: {}", command);
                            writeInt16(5004, getScaledInt16Value(command));
                            break;
                        case CHANNEL_HEATINGCIRCUIT1_SETPOINT_FLOW_LINE_TEMPERATURE:
                            // loggertrace("393 command: {}", command);
                            writeInt16(5005, getScaledInt16Value(command));
                            break;
                        case CHANNEL_HEATINGCIRCUIT1_OPERATING_MODE:
                            // loggertrace("403 command: {}", command);
                            writeInt16(5006, getInt16Value(command));
                            break;

                    }
                }

                if (GROUP_HEATINGCIRCUIT1SETTING.equals(channelUID.getGroupId())) {
                    // loggertrace("411 im HEATINGCIRCUI1 channelUID {} ", channelUID.getIdWithoutGroup());

                    switch (channelUID.getIdWithoutGroup()) {

                        case CHANNEL_HEATINGCIRCUIT1_OFFSET_FLOW_LINE_TEMPERATURE:

                            // loggertrace("418 command: {}", command);
                            writeInt16(5050, getScaledInt16Value(command));
                            break;
                        case CHANNEL_HEATINGCIRCUIT1_ROOM_HEATING_TEMPERATURE:

                            // loggertrace("233 command: {}", command);
                            writeInt16(5051, getScaledInt16Value(command));
                            break;
                        case CHANNEL_HEATINGCIRCUIT1_ROOM_COOLING_TEMPERATURE:

                            // loggertrace("427 command: {}", command);
                            writeInt16(5052, getScaledInt16Value(command));
                            break;

                    }

                }
                if (GROUP_HEATPUMP1SET.equals(channelUID.getGroupId())) {
                    // loggertrace("439 im HEATPUMP1SET channelUID {} ", channelUID.getIdWithoutGroup());

                    switch (channelUID.getIdWithoutGroup()) {

                        case CHANNEL_HEATPUMP1_SET_ERROR_QUIT:

                            // loggertrace("445 Heatpumpseterrorquit command: {}", command);
                            writeInt16(1050, getScaledInt16Value(command));
                            break;

                    }
                }
            } catch (LambdaException error) {
                if (hasConfigurationError() || getThing().getStatus() == ThingStatus.OFFLINE) {
                    return;
                }
                String cls = error.getClass().getName();
                String msg = error.getMessage();

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        String.format("Error with: %s: %s", cls, msg));
            }
        }
    }

    /**
     * Initialization: Load the config object of the block Connect to the slave
     * bridge Start the periodic polling
     */
    @Override
    public void initialize() {
        config = getConfigAs(LambdaConfiguration.class);
        logger.debug("Initializing thing with properties: {}", thing.getProperties());

        startUp();
    }

    /*
     * This method starts the operation of this handler Connect to the slave bridge
     * Start the periodic polling1
     */
    private void startUp() {
        if (comms != null) {
            return;
        }

        ModbusEndpointThingHandler slaveEndpointThingHandler = getEndpointThingHandler();
        if (slaveEndpointThingHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge is offline");
            return;
        }

        try {
            slaveId = slaveEndpointThingHandler.getSlaveId();

            comms = slaveEndpointThingHandler.getCommunicationInterface();
        } catch (EndpointNotInitializedException e) {
            // this will be handled below as endpoint remains null
        }

        if (comms == null) {
            @SuppressWarnings("null")
            String label = Optional.ofNullable(getBridge()).map(b -> b.getLabel()).orElse("<null>");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                    String.format("Bridge '%s' not completely initialized", label));
            return;
        }

        if (config == null) {
            logger.debug("Invalid comms/config/manager ref for stiebel eltron handler");
            return;
        }

        if (ambientPoller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledAmbientData(registers);
                }
            };
            poller.registerPollTask(0, 5, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            ambientPoller = poller;
        }

        if (emanagerPoller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledEManagerData(registers);
                }
            };
            poller.registerPollTask(100, 5, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            emanagerPoller = poller;
        }
        if (heatpump1Poller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledHeatpump1Data(registers);
                }
            };

            poller.registerPollTask(1000, 14, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            // loggertrace("Poller Heatpump1 erzeugt");
            heatpump1Poller = poller;
        }

        if (heatpump1SetPoller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledHeatpump1SetData(registers);
                }
            };

            poller.registerPollTask(1050, 1, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            // loggertrace("Poller Heatpump1Set erzeugt");
            heatpump1SetPoller = poller;
        }

        if (boiler1Poller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledBoiler1Data(registers);
                }
            };

            poller.registerPollTask(2000, 4, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            boiler1Poller = poller;
        }
        if (boiler1mtPoller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledBoiler1MtData(registers);
                }
            };

            poller.registerPollTask(2050, 1, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            boiler1mtPoller = poller;
        }

        if (buffer1Poller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledBuffer1Data(registers);
                }
            };

            poller.registerPollTask(3000, 4, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            buffer1Poller = poller;
        }
        if (buffer1mtPoller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledBuffer1MtData(registers);
                }
            };

            poller.registerPollTask(3050, 1, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            buffer1mtPoller = poller;
        }
        if (heatingcircuit1Poller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledHeatingCircuit1Data(registers);
                }
            };

            poller.registerPollTask(5000, 7, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            // loggertrace("Poller HeatingCircuit1 erzeugt");
            heatingcircuit1Poller = poller;
        }
        if (heatingcircuit1settingPoller == null) {
            AbstractBasePoller poller = new AbstractBasePoller() {
                @Override
                protected void handlePolledData(ModbusRegisterArray registers) {
                    handlePolledHeatingCircuit1SettingData(registers);
                }
            };

            poller.registerPollTask(5050, 3, ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS);
            // loggertrace("Poller HeatingCircuit1Setting erzeugt");
            heatingcircuit1settingPoller = poller;
        }

        updateStatus(ThingStatus.UNKNOWN);
    }

    /**
     * Dispose the binding correctly
     */
    @Override
    public void dispose() {
        tearDown();
    }

    /**
     * Unregister the poll tasks and release the endpoint reference
     */
    private void tearDown() {

        AbstractBasePoller poller = ambientPoller;
        if (poller != null) {
            poller.unregisterPollTask();
            ambientPoller = null;
        }

        poller = emanagerPoller;
        if (poller != null) {
            poller.unregisterPollTask();
            emanagerPoller = null;
        }

        poller = heatpump1Poller;
        if (poller != null) {
            poller.unregisterPollTask();
            heatpump1Poller = null;
        }

        poller = heatpump1SetPoller;
        if (poller != null) {
            poller.unregisterPollTask();
            heatpump1SetPoller = null;
        }
        poller = boiler1Poller;
        if (poller != null) {
            poller.unregisterPollTask();
            boiler1Poller = null;
        }
        poller = boiler1mtPoller;
        if (poller != null) {
            poller.unregisterPollTask();
            boiler1mtPoller = null;
        }

        poller = buffer1Poller;
        if (poller != null) {
            poller.unregisterPollTask();
            buffer1Poller = null;
        }
        poller = buffer1mtPoller;
        if (poller != null) {
            poller.unregisterPollTask();
            buffer1mtPoller = null;
        }

        poller = heatingcircuit1Poller;
        if (poller != null) {
            logger.debug("Unregistering heatingcircuit1Poller from ModbusManager");
            poller.unregisterPollTask();
            heatingcircuit1Poller = null;
        }

        poller = heatingcircuit1settingPoller;
        if (poller != null) {
            logger.debug("Unregistering heatingcircuit1settingPoller from ModbusManager");
            poller.unregisterPollTask();
            heatingcircuit1settingPoller = null;
        }
        comms = null;
    }

    /**
     * Returns the current slave id from the bridge
     */
    public int getSlaveId() {
        return slaveId;
    }

    /**
     * Get the endpoint handler from the bridge this handler is connected to Checks
     * that we're connected to the right type of bridge
     *
     * @return the endpoint handler or null if the bridge does not exist
     */
    private @Nullable ModbusEndpointThingHandler getEndpointThingHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            logger.debug("Bridge is null");
            return null;
        }
        if (bridge.getStatus() != ThingStatus.ONLINE) {
            logger.debug("Bridge is not online");
            return null;
        }

        ThingHandler handler = bridge.getHandler();
        if (handler == null) {
            logger.debug("Bridge handler is null");
            return null;
        }

        if (handler instanceof ModbusEndpointThingHandler thingHandler) {
            return thingHandler;
        } else {
            throw new IllegalStateException("Unexpected bridge handler: " + handler.toString());
        }
    }

    /**
     * Obsolete because of new getScaled below
     * 
     * Returns value divided by the 10
     *
     * @param value the value to alter
     * @return the scaled value as a DecimalType
     * 
     * 
     *         protected State getScaled10(Number value, Unit<?> unit) {
     *         // loggertrace("505 value: {}", value.intValue());
     *         return QuantityType.valueOf(value.doubleValue() / 10, unit);
     *         }
     * 
     *         protected State getScaled100(Number value, Unit<?> unit) {
     *         // loggertrace("505 value: {}", value.intValue());
     *         return QuantityType.valueOf(value.doubleValue() / 100, unit);
     *         }
     */
    protected State getScaled(Number value, Unit<?> unit, Double pow) {
        // loggertrace("505 value: {}", value.intValue());
        double factor = Math.pow(10, pow);
        return QuantityType.valueOf(value.doubleValue() * factor, unit);
    }

    protected State getUnscaled(Number value, Unit<?> unit) {
        return QuantityType.valueOf(value.doubleValue(), unit);
    }

    /**
     * Returns high value * 1000 + low value
     *
     * @param high the high value
     * @param low the low valze
     * @return the scaled value as a DecimalType
     */
    protected State getEnergyQuantity(int high, int low) {
        double value = high * 1000 + low;
        return QuantityType.valueOf(value, KILOWATT_HOUR);
    }

    /**
     * These methods are called each time new data has been polled from the modbus
     * slave The register array is first parsed, then each of the channels are
     * updated to the new values
     *
     * @param registers byte array read from the modbus slave
     */
    protected void handlePolledAmbientData(ModbusRegisterArray registers) {
        // loggertrace("Ambient block received, size: {}", registers.size());

        // Ambient group
        AmbientBlock block = ambientBlockParser.parse(registers);

        updateState(channelUID(GROUP_GENERAL_AMBIENT, CHANNEL_AMBIENT_ERROR_NUMBER),
                new DecimalType(block.ambientErrorNumber));
        updateState(channelUID(GROUP_GENERAL_AMBIENT, CHANNEL_AMBIENT_OPERATING_STATE),
                new DecimalType(block.ambientOperatingState));
        updateState(channelUID(GROUP_GENERAL_AMBIENT, CHANNEL_ACTUAL_AMBIENT_TEMPERATURE),
                getScaled(block.actualAmbientTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_GENERAL_AMBIENT, CHANNEL_AVERAGE_AMBIENT_TEMPERATURE),
                getScaled(block.averageAmbientTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_GENERAL_AMBIENT, CHANNEL_CALCULATED_AMBIENT_TEMPERATURE),
                getScaled(block.calculatedAmbientTemperature, CELSIUS, -1.0));
        resetCommunicationError();
    }

    protected void handlePolledEManagerData(ModbusRegisterArray registers) {
        // loggertrace("EManager block received, size: {}", registers.size());

        // EManager group
        EManagerBlock block = emanagerBlockParser.parse(registers);
        updateState(channelUID(GROUP_GENERAL_EMANAGER, CHANNEL_EMANAGER_ERROR_NUMBER),
                new DecimalType(block.emanagerErrorNumber));
        updateState(channelUID(GROUP_GENERAL_EMANAGER, CHANNEL_EMANAGER_OPERATING_STATE),
                new DecimalType(block.emanagerOperatingState));
        updateState(channelUID(GROUP_GENERAL_EMANAGER, CHANNEL_ACTUAL_POWER_CONSUMPTION),
                getUnscaled(block.actualPowerConsumption, WATT));
        updateState(channelUID(GROUP_GENERAL_EMANAGER, CHANNEL_ACTUAL_POWER), getUnscaled(block.actualPower, WATT));
        updateState(channelUID(GROUP_GENERAL_EMANAGER, CHANNEL_POWER_CONSUMPTION_SETPOINT),
                getUnscaled(block.powerConsumptionSetpoint, WATT));

        resetCommunicationError();
    }

    protected void handlePolledHeatpump1Data(ModbusRegisterArray registers) {
        // loggertrace("Heatpump1 block received, size: {}", registers.size());

        Heatpump1Block block = heatpump1BlockParser.parse(registers);

        // Heatpump1 group
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_ERROR_STATE),
                new DecimalType(block.heatpump1ErrorState));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_ERROR_NUMBER),
                new DecimalType(block.heatpump1ErrorNumber));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_OPERATING_STATE),
                new DecimalType(block.heatpump1OperatingState));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_STATE), new DecimalType(block.heatpump1State));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_T_FLOW),
                getScaled(block.heatpump1TFlow, CELSIUS, -2.0));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_T_RETURN),
                getScaled(block.heatpump1TReturn, CELSIUS, -2.0));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_VOL_SINK),
                getScaled(block.heatpump1VolSink, LITRE_PER_MINUTE, -2.0));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_T_EQIN),
                getScaled(block.heatpump1TEQin, CELSIUS, -2.0));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_T_EQOUT),
                getScaled(block.heatpump1TEQout, CELSIUS, -2.0));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_VOL_SOURCE),
                getScaled(block.heatpump1VolSource, LITRE_PER_MINUTE, -2.0));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_COMPRESSOR_RATING),
                getScaled(block.heatpump1CompressorRating, PERCENT, -2.0));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_QP_HEATING),
                getScaled(block.heatpump1QpHeating, WATT, 2.0));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_FI_POWER_CONSUMPTION),
                getUnscaled(block.heatpump1FIPowerConsumption, WATT));
        updateState(channelUID(GROUP_HEATPUMP1, CHANNEL_HEATPUMP1_COP), getScaled(block.heatpump1COP, PERCENT, -2.0));

        resetCommunicationError();
    }

    protected void handlePolledHeatpump1SetData(ModbusRegisterArray registers) {
        // loggertrace("Heatpump1Set block received, size: {}", registers.size());

        Heatpump1SetBlock block = heatpump1SetBlockParser.parse(registers);

        // Heatpump1 group
        updateState(channelUID(GROUP_HEATPUMP1SET, CHANNEL_HEATPUMP1_SET_ERROR_QUIT),
                new DecimalType(block.heatpump1seterrorquit));
        resetCommunicationError();
    }

    protected void handlePolledBoiler1Data(ModbusRegisterArray registers) {
        // loggertrace("Boiler1 block received, size: {}", registers.size());

        Boiler1Block block = boiler1BlockParser.parse(registers);

        // Boiler1 group
        updateState(channelUID(GROUP_BOILER1, CHANNEL_BOILER1_ERROR_NUMBER), new DecimalType(block.boiler1ErrorNumber));
        updateState(channelUID(GROUP_BOILER1, CHANNEL_BOILER1_OPERATING_STATE),
                new DecimalType(block.boiler1OperatingState));
        updateState(channelUID(GROUP_BOILER1, CHANNEL_BOILER1_ACTUAL_HIGH_TEMPERATURE),
                getScaled(block.boiler1ActualHighTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_BOILER1, CHANNEL_BOILER1_ACTUAL_LOW_TEMPERATURE),
                getScaled(block.boiler1ActualLowTemperature, CELSIUS, -1.0));
        resetCommunicationError();
    }

    protected void handlePolledBoiler1MtData(ModbusRegisterArray registers) {
        // loggertrace("Boiler1Mt block received, size: {}", registers.size());

        Boiler1MtBlock block = boiler1mtBlockParser.parse(registers);

        // Boiler1Mt group
        updateState(channelUID(GROUP_BOILER1MT, CHANNEL_BOILER1_MAXIMUM_BOILER_TEMPERATURE),
                getScaled(block.boiler1MaximumBoilerTemperature, CELSIUS, -1.0));
        resetCommunicationError();
    }

    protected void handlePolledBuffer1Data(ModbusRegisterArray registers) {
        // loggertrace("Buffer1 block received, size: {}", registers.size());

        Buffer1Block block = buffer1BlockParser.parse(registers);

        // Buffer1 group
        updateState(channelUID(GROUP_BUFFER1, CHANNEL_BUFFER1_ERROR_NUMBER), new DecimalType(block.buffer1ErrorNumber));
        updateState(channelUID(GROUP_BUFFER1, CHANNEL_BUFFER1_OPERATING_STATE),
                new DecimalType(block.buffer1OperatingState));
        updateState(channelUID(GROUP_BUFFER1, CHANNEL_BUFFER1_ACTUAL_HIGH_TEMPERATURE),
                getScaled(block.buffer1ActualHighTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_BUFFER1, CHANNEL_BUFFER1_ACTUAL_LOW_TEMPERATURE),
                getScaled(block.buffer1ActualLowTemperature, CELSIUS, -1.0));
        resetCommunicationError();
    }

    protected void handlePolledBuffer1MtData(ModbusRegisterArray registers) {
        // loggertrace("Buffer1Mt block received, size: {}", registers.size());

        Buffer1MtBlock block = buffer1mtBlockParser.parse(registers);

        // Buffer1Mt group
        updateState(channelUID(GROUP_BUFFER1MT, CHANNEL_BUFFER1_MAXIMUM_BOILER_TEMPERATURE),
                getScaled(block.buffer1MaximumBufferTemperature, CELSIUS, -1.0));
        resetCommunicationError();
    }

    protected void handlePolledHeatingCircuit1Data(ModbusRegisterArray registers) {
        // loggertrace("HeatingCircuit1 block received, size: {}", registers.size());

        HeatingCircuit1Block block = heatingcircuit1BlockParser.parse(registers);

        // HeatingCircuit1 group

        updateState(channelUID(GROUP_HEATINGCIRCUIT1, CHANNEL_HEATINGCIRCUIT1_ERROR_NUMBER),
                new DecimalType(block.heatingcircuit1ErrorNumber));
        updateState(channelUID(GROUP_HEATINGCIRCUIT1, CHANNEL_HEATINGCIRCUIT1_OPERATING_STATE),
                new DecimalType(block.heatingcircuit1OperatingState));
        updateState(channelUID(GROUP_HEATINGCIRCUIT1, CHANNEL_HEATINGCIRCUIT1_FLOW_LINE_TEMPERATURE),
                getScaled(block.heatingcircuit1FlowLineTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_HEATINGCIRCUIT1, CHANNEL_HEATINGCIRCUIT1_RETURN_LINE_TEMPERATURE),
                getScaled(block.heatingcircuit1ReturnLineTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_HEATINGCIRCUIT1, CHANNEL_HEATINGCIRCUIT1_ROOM_DEVICE_TEMPERATURE),
                getScaled(block.heatingcircuit1RoomDeviceTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_HEATINGCIRCUIT1, CHANNEL_HEATINGCIRCUIT1_SETPOINT_FLOW_LINE_TEMPERATURE),
                getScaled(block.heatingcircuit1SetpointFlowLineTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_HEATINGCIRCUIT1, CHANNEL_HEATINGCIRCUIT1_OPERATING_MODE),
                new DecimalType(block.heatingcircuit1OperatingMode));

        resetCommunicationError();
    }

    protected void handlePolledHeatingCircuit1SettingData(ModbusRegisterArray registers) {
        // loggertrace("HeatingCircuit1Setting block received, size: {}", registers.size());

        HeatingCircuit1SettingBlock block = heatingcircuit1settingBlockParser.parse(registers);

        // HeatingCircuit1Settting group

        updateState(channelUID(GROUP_HEATINGCIRCUIT1SETTING, CHANNEL_HEATINGCIRCUIT1_OFFSET_FLOW_LINE_TEMPERATURE),
                getScaled(block.heatingcircuit1OffsetFlowLineTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_HEATINGCIRCUIT1SETTING, CHANNEL_HEATINGCIRCUIT1_ROOM_HEATING_TEMPERATURE),
                getScaled(block.heatingcircuit1RoomHeatingTemperature, CELSIUS, -1.0));
        updateState(channelUID(GROUP_HEATINGCIRCUIT1SETTING, CHANNEL_HEATINGCIRCUIT1_ROOM_COOLING_TEMPERATURE),
                getScaled(block.heatingcircuit1RoomCoolingTemperature, CELSIUS, -1.0));
        resetCommunicationError();
    }

    /**
     * @param bridgeStatusInfo
     */
    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        super.bridgeStatusChanged(bridgeStatusInfo);

        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            startUp();
        } else if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            tearDown();
        }
    }

    /**
     * Handle errors received during communication
     */
    protected void handleReadError(AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
        // Ignore all incoming data and errors if configuration is not correct
        if (hasConfigurationError() || getThing().getStatus() == ThingStatus.OFFLINE) {
            return;
        }
        String msg = failure.getCause().getMessage();
        String cls = failure.getCause().getClass().getName();
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                String.format("Error with read: %s: %s", cls, msg));
    }

    /**
     * Handle errors received during communication
     */
    protected void handleWriteError(AsyncModbusFailure<ModbusWriteRequestBlueprint> failure) {
        // Ignore all incoming data and errors if configuration is not correct
        if (hasConfigurationError() || getThing().getStatus() == ThingStatus.OFFLINE) {
            return;
        }
        String msg = failure.getCause().getMessage();
        String cls = failure.getCause().getClass().getName();
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                String.format("Error with write: %s: %s", cls, msg));
    }

    /**
     * Returns true, if we're in a CONFIGURATION_ERROR state
     *
     * @return
     */
    protected boolean hasConfigurationError() {
        ThingStatusInfo statusInfo = getThing().getStatusInfo();
        return statusInfo.getStatus() == ThingStatus.OFFLINE
                && statusInfo.getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR;
    }

    /**
     * Reset communication status to ONLINE if we're in an OFFLINE state
     */
    protected void resetCommunicationError() {
        ThingStatusInfo statusInfo = thing.getStatusInfo();
        if (ThingStatus.OFFLINE.equals(statusInfo.getStatus())
                && ThingStatusDetail.COMMUNICATION_ERROR.equals(statusInfo.getStatusDetail())) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    /**
     * Returns the channel UID for the specified group and channel id
     *
     * @param string the channel group
     * @param string the channel id in that group
     * @return the globally unique channel uid
     */
    ChannelUID channelUID(String group, String id) {
        return new ChannelUID(getThing().getUID(), group, id);
    }
}
