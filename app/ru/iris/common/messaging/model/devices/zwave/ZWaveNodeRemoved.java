package ru.iris.common.messaging.model.devices.zwave;

import ru.iris.common.database.model.devices.Device;

/**
 * IRISv2 Project
 * Author: Nikolay A. Viguro
 * WWW: iris.ph-systems.ru
 * E-Mail: nv@ph-systems.ru
 * Date: 19.11.13
 * Time: 11:34
 * License: GPL v3
 */
public class ZWaveNodeRemoved extends ZWaveNode {

    public ZWaveNodeRemoved set(Device device) {
        super.device = device;
        return this;
    }

    @Override
    public String toString() {
        return "ZWaveNodeRemoved { node: " + super.device.getInternalName() + " }";
    }
}
