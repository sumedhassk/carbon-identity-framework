/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.mgt.internal;

import org.apache.axis2.engine.AxisObserver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.identity.core.util.IdentityCoreInitializedEvent;
import org.wso2.carbon.identity.mgt.IdentityMgtConfig;
import org.wso2.carbon.identity.mgt.IdentityMgtConfigException;
import org.wso2.carbon.identity.mgt.IdentityMgtEventListener;
import org.wso2.carbon.identity.mgt.RecoveryProcessor;
import org.wso2.carbon.identity.mgt.config.Config;
import org.wso2.carbon.identity.mgt.config.ConfigBuilder;
import org.wso2.carbon.identity.mgt.config.EmailNotificationConfig;
import org.wso2.carbon.identity.mgt.config.StorageType;
import org.wso2.carbon.identity.mgt.constants.IdentityMgtConstants;
import org.wso2.carbon.identity.mgt.listener.TenantManagementListener;
import org.wso2.carbon.identity.mgt.listener.UserOperationsNotificationListener;
import org.wso2.carbon.identity.mgt.store.RegistryCleanUpService;
import org.wso2.carbon.identity.mgt.util.UserIdentityManagementUtil;
import org.wso2.carbon.identity.notification.mgt.NotificationSender;
import org.wso2.carbon.registry.core.Collection;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.stratos.common.listeners.TenantMgtListener;
import org.wso2.carbon.user.core.listener.UserOperationEventListener;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.ConfigurationContextService;

import java.util.Dictionary;
import java.util.Hashtable;

/**
 * @scr.component name="org.wso2.carbon.identity.mgt.internal.IdentityMgtServiceComponent"
 * immediate="true"
 * @scr.reference name="registry.service"
 * interface="org.wso2.carbon.registry.core.service.RegistryService" cardinality="1..1"
 * policy="dynamic" bind="setRegistryService" unbind="unsetRegistryService"
 * @scr.reference name="realm.service"
 * interface="org.wso2.carbon.user.core.service.RealmService"cardinality="1..1"
 * policy="dynamic" bind="setRealmService" unbind="unsetRealmService"
 * @scr.reference name="carbon.identity.notification.mgt"
 * interface="org.wso2.carbon.identity.notification.mgt.NotificationSender"
 * cardinality="1..1" policy="dynamic" bind="setNotificationSender"
 * unbind="unsetNotificationSender"
 * @scr.reference name="identityCoreInitializedEventService"
 * interface="org.wso2.carbon.identity.core.util.IdentityCoreInitializedEvent" cardinality="1..1"
 * policy="dynamic" bind="setIdentityCoreInitializedEventService" unbind="unsetIdentityCoreInitializedEventService"
 */

public class IdentityMgtServiceComponent {

    private static final String DELAY_BETWEEN_RUNS = "TimeConfig.RegistryCleanUpPeriod";
    private static Log log = LogFactory.getLog(IdentityMgtServiceComponent.class);

    private static RealmService realmService;

    private static RegistryService registryService;

    private static ConfigurationContextService configurationContextService;
    private static RecoveryProcessor recoveryProcessor;
    private static NotificationSender notificationSender;

    public static RealmService getRealmService() {
        return realmService;
    }

    protected void setRealmService(RealmService realmService) {
        log.debug("Setting the Realm Service");
        IdentityMgtServiceComponent.realmService = realmService;
    }

    public static RegistryService getRegistryService() {
        return registryService;
    }

    protected void setRegistryService(RegistryService registryService) {
        log.debug("Setting the Registry Service");
        IdentityMgtServiceComponent.registryService = registryService;
    }

    public static ConfigurationContextService getConfigurationContextService() {
        return configurationContextService;
    }

    protected void setConfigurationContextService(ConfigurationContextService configurationContextService) {
        log.debug("Setting theConfigurationContext Service");
        IdentityMgtServiceComponent.configurationContextService = configurationContextService;

    }

    public static RecoveryProcessor getRecoveryProcessor() {
        return recoveryProcessor;
    }

    private static void init() {

        Registry registry;
        IdentityMgtConfig.getInstance(realmService.getBootstrapRealmConfiguration());
        recoveryProcessor = new RecoveryProcessor();
        try {
            registry = IdentityMgtServiceComponent.getRegistryService().getConfigSystemRegistry();
            if (!registry
                    .resourceExists(IdentityMgtConstants.IDENTITY_MANAGEMENT_PATH)) {
                Collection questionCollection = registry.newCollection();
                registry.put(IdentityMgtConstants.IDENTITY_MANAGEMENT_PATH,
                        questionCollection);
                UserIdentityManagementUtil.loadDefaultChallenges();
            }

            Config emailConfigFile = ConfigBuilder.getInstance().loadEmailConfigFile();
            EmailNotificationConfig emailNotificationConfig = new EmailNotificationConfig();
            emailNotificationConfig.setProperties(emailConfigFile.getProperties());
            ConfigBuilder.getInstance().saveConfiguration(StorageType.REGISTRY, MultitenantConstants.SUPER_TENANT_ID,
                                                          emailNotificationConfig);

        } catch (RegistryException e) {
            log.error("Error while creating registry collection for org.wso2.carbon.identity.mgt component", e);
        } catch (IdentityMgtConfigException e) {
            log.error("Error occurred while saving default email templates in registry for super tenant");
        }

    }

    protected void activate(ComponentContext context) {

        Dictionary<String, String> props = new Hashtable<String, String>();
        props.put(CarbonConstants.AXIS2_CONFIG_SERVICE, AxisObserver.class.getName());
        context.getBundleContext().registerService(AxisObserver.class.getName(),
                new IdentityMgtDeploymentInterceptor(), props);
        init();

        if (log.isDebugEnabled()) {
            log.debug("Identity Management Listener is enabled");
        }

        ServiceRegistration serviceRegistration = context.getBundleContext().registerService
                (UserOperationEventListener.class.getName(), new IdentityMgtEventListener(), null);
        if (serviceRegistration != null) {
            if (log.isDebugEnabled()) {
                log.debug("Identity Management - UserOperationEventListener registered.");
            }
        } else {
            log.error("Identity Management - UserOperationEventListener could not be registered.");
        }

        UserOperationsNotificationListener notificationListener =
                new UserOperationsNotificationListener();
        ServiceRegistration userOperationNotificationSR = context.getBundleContext().registerService(
                UserOperationEventListener.class.getName(), notificationListener, null);
        context.getBundleContext().registerService(TenantMgtListener.class.getName(), new TenantManagementListener()
                , null);

        if (userOperationNotificationSR != null) {
            if (log.isDebugEnabled()) {
                log.debug("Identity Management - UserOperationNotificationListener registered.");
            }
        } else {
            log.error("Identity Management - UserOperationNotificationListener could not be registered.");
        }

        if(log.isDebugEnabled()) {
            log.debug("Identity Management bundle is activated");
        }

        RegistryCleanUpService registryCleanUpService = new RegistryCleanUpService(IdentityMgtConfig.getInstance()
                .getRegistryCleanUpPeriod(), IdentityMgtConfig.getInstance().getRegistryCleanUpPeriod());
        registryCleanUpService.activateCleanUp();
    }

    protected void deactivate(ComponentContext context) {
        log.debug("Identity Management bundle is de-activated");
    }

    protected void unsetRegistryService(RegistryService registryService) {
        log.debug("UnSetting the Registry Service");
        IdentityMgtServiceComponent.registryService = null;
    }

    protected void unsetRealmService(RealmService realmService) {
        log.debug("UnSetting the Realm Service");
        IdentityMgtServiceComponent.realmService = null;
    }

    protected void unsetConfigurationContextService(ConfigurationContextService configurationContextService) {
        log.debug("UnSetting the  ConfigurationContext Service");
        IdentityMgtServiceComponent.configurationContextService = null;
    }

    protected void setNotificationSender(NotificationSender notificationSender) {
        if (log.isDebugEnabled()) {
            log.debug("Un-setting notification sender in Entitlement bundle");
        }
        this.notificationSender = notificationSender;
    }

    protected void unsetNotificationSender(NotificationSender notificationSender) {
        if (log.isDebugEnabled()) {
            log.debug("Setting notification sender in Entitlement bundle");
        }
        this.notificationSender = null;
    }

    protected void setIdentityCoreInitializedEventService(IdentityCoreInitializedEvent identityCoreInitializedEvent) {
        /* reference IdentityCoreInitializedEvent service to guarantee that this component will wait until identity core
         is started */
    }

    protected void unsetIdentityCoreInitializedEventService(IdentityCoreInitializedEvent identityCoreInitializedEvent) {
        /* reference IdentityCoreInitializedEvent service to guarantee that this component will wait until identity core
         is started */
    }

    public static NotificationSender getNotificationSender() {
        return IdentityMgtServiceComponent.notificationSender;
    }

}
