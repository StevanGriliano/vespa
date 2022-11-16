package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.CloudAccount;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.tenant.Tenant;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

public class EnclaveAccessMaintainer extends ControllerMaintainer {

    private static final Logger logger = Logger.getLogger(EnclaveAccessMaintainer.class.getName());

    EnclaveAccessMaintainer(Controller controller, Duration interval) {
        super(controller, interval);
    }

    @Override
    protected double maintain() {
        try {
            controller().serviceRegistry().enclaveAccessService().allowAccessFor(externalAccounts());
            return 1;
        }
        catch (RuntimeException e) {
            logger.log(WARNING, "Failed sharing AMIs", e);
            return 0;
        }
    }

    private Set<CloudAccount> externalAccounts() {
        Set<CloudAccount> accounts = new HashSet<>();
        for (Tenant tenant : controller().tenants().asList())
            accounts.addAll(controller().applications().accountsOf(tenant.name()));

        return accounts;
    }


}
