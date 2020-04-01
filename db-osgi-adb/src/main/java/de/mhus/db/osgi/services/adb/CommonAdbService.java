/**
 * Copyright 2018 Mike Hummel
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mhus.db.osgi.services.adb;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.UUID;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import de.mhus.db.osgi.api.adb.AdbService;
import de.mhus.db.osgi.api.adb.CommonAdbConsumer;
import de.mhus.db.osgi.api.adb.Reference;
import de.mhus.db.osgi.api.adb.Reference.TYPE;
import de.mhus.db.osgi.api.adb.ReferenceCollector;
import de.mhus.lib.adb.DbManager;
import de.mhus.lib.adb.DbSchema;
import de.mhus.lib.adb.Persistable;
import de.mhus.lib.basics.UuidIdentificable;
import de.mhus.lib.core.MApi;
import de.mhus.lib.core.MThread;
import de.mhus.lib.core.cfg.CfgBoolean;
import de.mhus.lib.errors.MException;
import de.mhus.lib.sql.DataSourceProvider;
import de.mhus.lib.sql.DbPool;
import de.mhus.lib.sql.DefaultDbPool;
import de.mhus.lib.sql.PseudoDbPool;
import de.mhus.osgi.api.aaa.ContextCachedItem;
import de.mhus.osgi.api.services.MOsgi;

@Component(service = AdbService.class, immediate = true)
public class CommonAdbService extends AbstractAdbService {

    private static final CfgBoolean CFG_USE_PSEUDO =
            new CfgBoolean(CommonAdbService.class, "usePseudoPool", false);
    private static final CfgBoolean CFG_ENABLED =
            new CfgBoolean(CommonAdbService.class, "enabled", true);
    
    private ServiceTracker<CommonAdbConsumer, CommonAdbConsumer> tracker;
    private TreeMap<String, CommonAdbConsumer> schemaList = new TreeMap<>();

    private BundleContext context;

    enum STATUS {
        NONE,
        ACTIVATED,
        STARTED,
        CLOSED
    }

    private STATUS status = STATUS.NONE;
    private static CommonAdbService instance;

    public static CommonAdbService instance() {
        return instance;
    }
    
    @Activate
    public void doActivate(ComponentContext ctx) {
        status = STATUS.ACTIVATED;
        //		new de.mhus.lib.adb.util.Property();
        context = ctx.getBundleContext();

        if (context == null) return;

        instance = this;
        if (!CFG_ENABLED.value()) {
            log().i("not enabled");
            return;
        }
        MOsgi.runAfterActivation(ctx, this::doStart);
    }

    public void doStart(ComponentContext ctx) {
        while (true) {
            if (status == STATUS.STARTED) return;

            if (getManager() != null) {
                log().i("Start tracker");
                try {
                    tracker =
                            new ServiceTracker<>(
                                    context, CommonAdbConsumer.class, new MyTrackerCustomizer());
                    tracker.open();
                } finally {
                    status = STATUS.STARTED;
                }
                return;
            }
            log().i("Waiting for datasource",getDataSourceName());
            MThread.sleep(10000);
        }
    }

    @Deactivate
    public void doDeactivate(ComponentContext ctx) {
        status = STATUS.CLOSED;
        //		super.doDeactivate(ctx);
        if (tracker != null) tracker.close();
        tracker = null;
        context = null;
        schemaList.clear();
        instance = null;
    }

    @Override
    protected DbSchema doCreateSchema() {
        return new CommonDbSchema(this);
    }

    @Override
    public void doInitialize() throws MException {
        setDataSourceName(
                MApi.getCfg(AdbService.class).getExtracted("dataSourceName", "db_sop"));
    }

    @Override
    protected DbPool doCreateDataPool() {
        if (CFG_USE_PSEUDO.value())
            return new PseudoDbPool(
                    new DataSourceProvider(
                            getDataSource(),
                            doCreateDialect(),
                            doCreateConfig(),
                            doCreateActivator()));
        else
            return new DefaultDbPool(
                    new DataSourceProvider(
                            getDataSource(),
                            doCreateDialect(),
                            doCreateConfig(),
                            doCreateActivator()));
    }

    private class MyTrackerCustomizer
            implements ServiceTrackerCustomizer<CommonAdbConsumer, CommonAdbConsumer> {

        @Override
        public CommonAdbConsumer addingService(ServiceReference<CommonAdbConsumer> reference) {

            CommonAdbConsumer service = context.getService(reference);
            String name = service.getClass().getCanonicalName();
            service.doInitialize(CommonAdbService.this.getManager());

            synchronized (schemaList) {
                schemaList.put(name, service);
                updateManager();
            }

            if (CommonAdbService.this.getManager() != null) {
                servicePostInitialize(service, name);
            }
            return service;
        }

        @Override
        public void modifiedService(
                ServiceReference<CommonAdbConsumer> reference, CommonAdbConsumer service) {

            synchronized (schemaList) {
                updateManager();
            }
        }

        @Override
        public void removedService(
                ServiceReference<CommonAdbConsumer> reference, CommonAdbConsumer service) {

            String name = service.getClass().getCanonicalName();
            service.doDestroy();
            synchronized (schemaList) {
                schemaList.remove(name);
                updateManager();
            }
        }
    }

    protected void updateManager() {
        try {
            DbManager m = getManager();
            if (m != null) m.reconnect();
        } catch (Exception e) {
            log().e(e);
        }
    }

    protected void servicePostInitialize(CommonAdbConsumer service, String name) {
        MThread.asynchron(
                new Runnable() {
                    @Override
                    public void run() {
                        // wait for STARTED
                        while (status == STATUS.ACTIVATED
                                || CommonAdbService.this.getManager().getPool() == null) {
                            log().d("Wait for start", service);
                            MThread.sleep(250);
                        }
                        // already open
                        log().d("addingService", "doPostInitialize", name);
                        try {
                            service.doPostInitialize(CommonAdbService.this.getManager());
                        } catch (Throwable t) {
                            log().w(name, t);
                        }
                    }
                });
    }

    public CommonAdbConsumer[] getConsumer() {
        synchronized (schemaList) {
            return schemaList.values().toArray(new CommonAdbConsumer[schemaList.size()]);
        }
    }

    @Override
    public String getServiceName() {
        return MApi.getCfg(AdbService.class).getString("serviceName", "common");
    }

    @Override
    protected void doPostOpen() throws MException {
        synchronized (schemaList) {
            schemaList.forEach(
                    (name, service) -> {
                        log().d("doPostOpen", "doPostInitialize", name);
                        servicePostInitialize(service, name);
                    });
        }
    }

    public STATUS getStatus() {
        return status;
    }
    
    // ----
    // Access
    
    public CommonAdbConsumer getConsumer(String type) throws MException {
        if (type == null) throw new MException("type is null");
        CommonAdbConsumer ret =  schemaList.get(type);
        if (ret == null) throw new MException("Access Controller not found", type);
        return ret;
    }
    
    protected boolean canRead(Persistable obj) throws MException {
        if (obj == null) return false;

//XXX        Boolean item = ((AaaContextImpl) c).getCached("ace_read|" + obj.getId());
//        if (item != null) return item;

        CommonAdbConsumer controller = getConsumer(obj.getClass().getCanonicalName());
        if (controller == null) return false;

        ContextCachedItem ret = new ContextCachedItem();
        ret.bool = controller.canRead(obj);
//        ((AaaContextImpl) c)
//                .setCached("ace_read|" + obj.getId(), MPeriod.MINUTE_IN_MILLISECOUNDS * 5, ret);
        return ret.bool;
    }

    protected boolean canUpdate(Persistable obj) throws MException {
        if (obj == null) return false;

//        Boolean item = ((AaaContextImpl) c).getCached("ace_update|" + obj.getId());
//        if (item != null) return item;

        CommonAdbConsumer controller = getConsumer(obj.getClass().getCanonicalName());
        if (controller == null) return false;

        ContextCachedItem ret = new ContextCachedItem();
        ret.bool = controller.canUpdate(obj);
//        ((AaaContextImpl) c)
//               .setCached("ace_update|" + obj.getId(), MPeriod.MINUTE_IN_MILLISECOUNDS * 5, ret);
        return ret.bool;
    }

    protected boolean canDelete(Persistable obj) throws MException {
        if (obj == null) return false;

//        Boolean item = ((AaaContextImpl) c).getCached("ace_delete" + "|" + obj.getId());
//        if (item != null) return item;

        CommonAdbConsumer controller = getConsumer(obj.getClass().getCanonicalName());
        if (controller == null) return false;

        ContextCachedItem ret = new ContextCachedItem();
        ret.bool = controller.canDelete(obj);
//        ((AaaContextImpl) c)
//                .setCached("ace_delete|" + obj.getId(), MPeriod.MINUTE_IN_MILLISECOUNDS * 5, ret);
        return ret.bool;
    }

    protected boolean canCreate(Persistable obj) throws MException {
        if (obj == null) return false;
//        Boolean item = ((AaaContextImpl) c).getCached("ace_create" + "|" + obj.getId());
//        if (item != null) return item;

        CommonAdbConsumer controller = getConsumer(obj.getClass().getCanonicalName());
        if (controller == null) return false;

        ContextCachedItem ret = new ContextCachedItem();
        ret.bool = controller.canCreate(obj);
//        ((AaaContextImpl) c)
//                .setCached("ace_create|" + obj.getId(), MPeriod.MINUTE_IN_MILLISECOUNDS * 5, ret);
        return ret.bool;
    }

    @SuppressWarnings("unchecked")
    public <T extends Persistable> T getObject(String type, UUID id) throws MException {
        CommonAdbConsumer controller = getConsumer(type);
        if (controller == null) return null;
        return (T) controller.getObject(type, id);
    }

    @SuppressWarnings("unchecked")
    public <T extends Persistable> T getObject(String type, String id) throws MException {
        CommonAdbConsumer controller = getConsumer(type);
        if (controller == null) return null;
        return (T) controller.getObject(type, id);
    }

    protected void onDelete(Persistable object) {

        if (object == null) return;

        ReferenceCollector collector =
                new ReferenceCollector() {
                    LinkedList<UUID> list = new LinkedList<UUID>();

                    @Override
                    public void foundReference(Reference<?> ref) {
                        if (ref.getType() == TYPE.CHILD) {
                            if (ref.getObject() == null) return;
                            // be sure not cause an infinity loop, a object should only be deleted
                            // once ...
                            if (ref.getObject() instanceof UuidIdentificable) {
                                if (list.contains(((UuidIdentificable) ref.getObject()).getId()))
                                    return;
                                list.add(((UuidIdentificable) ref.getObject()).getId());
                            }
                            // delete the object and dependencies
                            try {
                                doDelete(ref);
                            } catch (MException e) {
                                log().w(
                                                "deletion failed",
                                                ref.getObject(),
                                                ref.getObject().getClass(),
                                                e);
                            }
                        }
                    }
                };

        collectRefereces(object, collector);
    }

    protected void doDelete(Reference<?> ref) throws MException {
        log().d("start delete", ref.getObject(), ref.getType());
        onDelete(ref.getObject());
        log().d("delete", ref);
        getManager().delete(ref.getObject());
    }

    public void collectRefereces(Persistable object, ReferenceCollector collector) {

        if (object == null) return;

        HashSet<CommonAdbConsumer> distinct = new HashSet<CommonAdbConsumer>();
        synchronized (schemaList) {
            distinct.addAll(schemaList.values());
        }

        for (CommonAdbConsumer service : distinct)
            try {
                service.collectReferences(object, collector);
            } catch (Throwable t) {
                log().w(service.getClass(), object.getClass(), t);
            }
    }

    public <T extends Persistable> T getObject(Class<T> type, UUID id) throws MException {
        return getObject(type.getCanonicalName(), id);
    }

}