/**
 *	Copyright 2018 SmartThings
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed

 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
	definition (name: "Fibaro Heat Controller", namespace: "smartthings", author: "Samsung", mnmn: "SmartThings", vid: "SmartThings-smartthings-Z-Wave_Battery_Thermostat", ocfDeviceType: "oic.d.thermostat") {
		capability "Thermostat Mode"
		capability "Refresh"
		capability "Battery"
		capability "Health Check"
		capability "Thermostat"
		capability "Thermostat Heating Setpoint"

		command "setThermostatSetpointUp"
		command "setThermostatSetpointDown"
		command "switchMode"

		fingerprint mfr: "010F", prod: "1301", model: "1000", deviceJoinName: "Fibaro Heat Controller"
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"thermostat", type:"general", width:6, height:4, canChangeIcon: false)  {
			tileAttribute("device.heatingSetpoint", key: "VALUE_CONTROL") {
				attributeState("VALUE_UP", action: "setThermostatSetpointUp")
				attributeState("VALUE_DOWN", action: "setThermostatSetpointDown")
			}
			tileAttribute("device.thermostatMode", key: "PRIMARY_CONTROL") {
				attributeState("off", action:"switchMode", nextState:"...", icon: "st.thermostat.heating-cooling-off", label: '${currentValue}')
				attributeState("heat", action:"switchMode", nextState:"...", icon: "st.thermostat.heat", label: '${currentValue}')
				attributeState("emergency heat", action:"switchMode", nextState:"...", icon: "st.thermostat.emergency-heat", label: '${currentValue}')
			}
		}

		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "battery", label: 'Battery:\n${currentValue}%', unit: "%"
		}

		standardTile("refresh", "command.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "refresh", label: 'refresh', action: "refresh.refresh", icon: "st.secondary.refresh-icon"
		}
		main "thermostat"
		details(["thermostat", "battery", "refresh"])
	}
}

def installed() {
	initialize()
}

def updated() {
	initialize()
}

def initialize() {
	def supportedModes = ["off", "emergency heat", "heat"]
	state.supportedModes = supportedModes
	sendEvent(name: "supportedThermostatModes", value: supportedModes, displayed: false)
	sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
	response([
			refresh(),
			setThermostatMode("off")
	])
}

def parse(String description) {
	def result = null
	def cmd = zwave.parse(description)
	if (cmd) {
		result = zwaveEvent(cmd)
	} else {
		log.warn "${device.displayName} - no-parsed event: ${description}"
	}
	log.debug "Parse returned: ${result}"
	return result
}

private commandClasses() {
	[0x70: 1]
}


def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapsulatedCommand = cmd.encapsulatedCommand(commandClasses())
	if (encapsulatedCommand) {
		log.debug "SecurityMessageEncapsulation into: ${encapsulatedCommand}"
		zwaveEvent(encapsulatedCommand)
	} else {
		log.warn "unable to extract secure command from $cmd"
		createEvent(descriptionText: cmd.toString())
	}
}

def zwaveEvent(physicalgraph.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
	def encapsulatedCommand = cmd.encapsulatedCommand(commandClasses())
	if (encapsulatedCommand) {
		log.debug "MultiChannel Encapsulation: ${encapsulatedCommand}"
		zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint)
	} else {
		log.warn "unable to extract multi channel command from $cmd"
		createEvent(descriptionText: cmd.toString())
	}
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd, sourceEndPoint = null) {
	def value = cmd.batteryLevel == 255 ? 1 : cmd.batteryLevel
	def map = [name: "battery", value: value]
	switch(sourceEndPoint) {
		case 1:
			createEvent(map)
			break
		case 2:
			if(cmd.batteryLevel > 0) {
				changeDeviceType()
			}
			break
	}
}

def zwaveEvent(physicalgraph.zwave.commands.thermostatmodev2.ThermostatModeReport cmd, sourceEndPoint = null) {
	def mode
	switch (cmd.mode) {
		case 1:
			mode = "heat"
			break
		case 31:
			mode = "emergency heat"
			break
		case 0:
			mode = "off"
			break
	}

	createEvent(name: "thermostatMode", value: mode)
}

def zwaveEvent(physicalgraph.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport cmd, sourceEndPoint = null) {
	createEvent(name: "heatingSetpoint", value: convertTemperatureIfNeeded(cmd.scaledValue, 'C', cmd.precision), unit: temperatureScale)
}

def zwaveEvent(physicalgraph.zwave.commands.configurationv1.ConfigurationReport cmd) {
	if(cmd.parameterNumber == 3 && cmd.scaledConfigurationValue == 1) {
		changeDeviceType()
	}
}

def zwaveEvent(physicalgraph.zwave.commands.applicationstatusv1.ApplicationBusy cmd) {
	log.warn "Device is busy, delaying refresh"
	runIn(15, "delayedRefresh", [overwrite: true])
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
	log.warn "Unhandled command: ${cmd}"
	[:]
}

def setThermostatMode(String mode) {
	def modeValue = 0
	switch (mode) {
		case "heat":
			modeValue = 1
			break
		case "emergency heat":
			modeValue = 31
			break
		case "off":
			modeValue = 0
			break
	}

	[
			secureEncap(zwave.thermostatModeV2.thermostatModeSet(mode: modeValue), 1),
			"delay 2000",
			secureEncap(zwave.thermostatModeV2.thermostatModeGet(), 1)
	]
}

def heat() {
	setThermostatMode("heat")
}

def off() {
	setThermostatMode("off")
}

def emergencyHeat() {
	setThermostatMode("emergency heat")
}

def setHeatingSetpoint(setpoint) {
	setpoint = temperatureScale == 'C' ? setpoint : fahrenheitToCelsius(setpoint)
	[
			secureEncap(zwave.thermostatSetpointV2.thermostatSetpointSet([precision: 1, scale: 0, scaledValue: setpoint, setpointType: 1, size: 2]), 1),
			"delay 2000",
			secureEncap(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1), 1)
	]
}

def setThermostatSetpointUp() {
	def setpoint = device.latestValue("heatingSetpoint")
	if (setpoint < maxHeatingSetpointTemperature) {
		setpoint = setpoint + (temperatureScale == 'C' ? 0.5 : 1)
	}
	setHeatingSetpoint(setpoint)
}

def setThermostatSetpointDown() {
	def setpoint = device.latestValue("heatingSetpoint")
	if (setpoint > minHeatingSetpointTemperature) {
		setpoint = setpoint - (temperatureScale == 'C' ? 0.5 : 1)
	}
	setHeatingSetpoint(setpoint)
}

def refresh() {
	def cmds = [
			secureEncap(zwave.batteryV1.batteryGet(), 1),
			secureEncap(zwave.batteryV1.batteryGet(), 2),
			secureEncap(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1), 1),
			secureEncap(zwave.thermostatModeV2.thermostatModeGet(),1),
			secureEncap(zwave.configurationV1.configurationGet(parameterNumber: 3), 1)
	]

	delayBetween(cmds, 2500)
}

def ping() {
	refresh()
}

private secureEncap(cmd, endpoint = null) {
	secure(encap(cmd, endpoint))
}

private secure(cmd) {
	if(zwaveInfo.zw.endsWith("s")) {
		zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	} else {
		cmd.format()
	}
}

private encap(cmd, endpoint = null) {
	if (endpoint) {
		zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint:endpoint).encapsulate(cmd)
	} else {
		cmd
	}
}

def switchMode() {
	def currentMode = device.currentValue("thermostatMode")
	def supportedModes = state.supportedModes
	if (supportedModes && supportedModes.size() && supportedModes[0].size() > 1) {
		def next = { supportedModes[supportedModes.indexOf(it) + 1] ?: supportedModes[0] }
		def nextMode = next(currentMode)
		setThermostatMode(nextMode)
	} else {
		log.warn "supportedModes not defined"
	}
}

private delayedRefresh() {
	sendHubCommand(refresh())
}

private changeDeviceType() {
	setDeviceType("Fibaro Heat Controller With Sensor")
}

private getMaxHeatingSetpointTemperature() {
	temperatureScale == 'C' ? 30 : 86
}

private getMinHeatingSetpointTemperature() {
	temperatureScale == 'C' ? 10 : 50
}