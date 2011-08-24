/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.ejb3.component.entity;

import org.jboss.as.ee.component.ComponentConfiguration;
import org.jboss.as.ee.component.ComponentStartService;
import org.jboss.as.ee.component.ComponentView;
import org.jboss.as.ee.component.DependencyConfigurator;
import org.jboss.as.ee.component.ViewConfiguration;
import org.jboss.as.ee.component.ViewConfigurator;
import org.jboss.as.ee.component.ViewDescription;
import org.jboss.as.ee.component.interceptors.InterceptorOrder;
import org.jboss.as.ejb3.component.ComponentTypeIdentityInterceptorFactory;
import org.jboss.as.ejb3.component.entity.interceptors.EntityBeanHomeCreateInterceptorFactory;
import org.jboss.as.ejb3.component.entity.interceptors.EntityBeanHomeFinderInterceptorFactory;
import org.jboss.as.ejb3.component.entity.interceptors.EntityBeanHomeMethodInterceptorFactory;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.reflect.ClassReflectionIndex;
import org.jboss.as.server.deployment.reflect.DeploymentReflectionIndex;
import org.jboss.msc.service.ServiceBuilder;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * View configurator for the home interface of an entity bean
 *
 * @author Stuart Douglas
 */
public class EntityBeanHomeViewConfigurator implements ViewConfigurator {

    @Override
    public void configure(final DeploymentPhaseContext context, final ComponentConfiguration componentConfiguration, final ViewDescription description, final ViewConfiguration configuration) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = context.getDeploymentUnit();
        final DeploymentReflectionIndex deploymentReflectionIndex = deploymentUnit.getAttachment(Attachments.REFLECTION_INDEX);

        configuration.addClientPostConstructInterceptor(org.jboss.invocation.Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ClientPostConstruct.TERMINAL_INTERCEPTOR);
        configuration.addClientPreDestroyInterceptor(org.jboss.invocation.Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ClientPreDestroy.TERMINAL_INTERCEPTOR);

        configuration.addViewPostConstructInterceptor(org.jboss.invocation.Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ViewPostConstruct.TERMINAL_INTERCEPTOR);
        configuration.addViewPreDestroyInterceptor(org.jboss.invocation.Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ViewPreDestroy.TERMINAL_INTERCEPTOR);


        final EntityBeanComponentDescription componentDescription = (EntityBeanComponentDescription) componentConfiguration.getComponentDescription();

        for (final Method method : configuration.getProxyFactory().getCachedMethods()) {

            configuration.addClientInterceptor(method, ViewDescription.CLIENT_DISPATCHER_INTERCEPTOR_FACTORY, InterceptorOrder.Client.CLIENT_DISPATCHER);

            if (method.getName().equals("equals") && method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == Object.class) {
                configuration.addClientInterceptor(method, ComponentTypeIdentityInterceptorFactory.INSTANCE, InterceptorOrder.Client.EJB_EQUALS_HASHCODE);
            } else if (method.getName().equals("hashCode") && method.getParameterTypes().length == 0) {
                configuration.addClientInterceptor(method, ComponentTypeIdentityInterceptorFactory.INSTANCE, InterceptorOrder.Client.EJB_EQUALS_HASHCODE);
            } else if (method.getName().equals("toString") && method.getParameterTypes().length == 0) {
                //TODO: toString
            } else if (method.getName().startsWith("create")) {
                //we have a create method.
                //lets resolve the corresponding ejbCreate method
                final Method ejbCreate = resolveEjbMethod("create", "ejbCreate", componentDescription.getPrimaryKeyType(), componentConfiguration.getComponentClass(), deploymentReflectionIndex, method, componentConfiguration.getComponentName());
                final Method ejbPostCreate = resolveEjbMethod("create", "ejbPostCreate", void.class.getName(), componentConfiguration.getComponentClass(), deploymentReflectionIndex, method, componentConfiguration.getComponentName());

                //we have a create method
                final ViewDescription createdView = componentDescription.getEjbLocalView();

                final EntityBeanHomeCreateInterceptorFactory factory = new EntityBeanHomeCreateInterceptorFactory(ejbCreate, ejbPostCreate);
                //add a dependency on the view to create
                componentConfiguration.getStartDependencies().add(new DependencyConfigurator<ComponentStartService>() {
                    @Override
                    public void configureDependency(final ServiceBuilder<?> serviceBuilder, final ComponentStartService service) throws DeploymentUnitProcessingException {
                        serviceBuilder.addDependency(createdView.getServiceName(), ComponentView.class, factory.getViewToCreate());
                    }
                });
                //add the interceptor
                configuration.addViewInterceptor(method, factory, InterceptorOrder.View.HOME_CREATE_INTERCEPTOR);

            } else if (method.getName().startsWith("find")) {
                final Method ejbFind = resolveEjbFinderMethod(componentConfiguration.getComponentClass(), deploymentReflectionIndex, method, componentConfiguration.getComponentName());

                final ViewDescription createdView = componentDescription.getEjbLocalView();

                final EntityBeanHomeFinderInterceptorFactory interceptorFactory = new EntityBeanHomeFinderInterceptorFactory(ejbFind);
                componentConfiguration.getStartDependencies().add(new DependencyConfigurator<ComponentStartService>() {
                    @Override
                    public void configureDependency(final ServiceBuilder<?> serviceBuilder, final ComponentStartService service) throws DeploymentUnitProcessingException {
                        serviceBuilder.addDependency(createdView.getServiceName(), ComponentView.class, interceptorFactory.getViewToCreate());
                    }
                });

                configuration.addViewInterceptor(method, interceptorFactory, InterceptorOrder.View.COMPONENT_DISPATCHER);

            } else if (method.getName().equals("remove") && method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == Object.class) {
                //TODO: remove by pk
            } else {
                //we have a home business method
                Method home = resolveEjbHomeBusinessMethod(componentConfiguration.getComponentClass(), deploymentReflectionIndex, method, componentConfiguration.getComponentName());
                configuration.addViewInterceptor(method, new EntityBeanHomeMethodInterceptorFactory(home), InterceptorOrder.View.COMPONENT_DISPATCHER);
            }
        }
    }


    private Method resolveEjbMethod(final String userName, final String ejbMethodName, final String returnType, final Class<?> componentClass, final DeploymentReflectionIndex index, final Method method, final String ejbName) throws DeploymentUnitProcessingException {

        final String name = method.getName().replaceFirst(userName, ejbMethodName);
        Class<?> clazz = componentClass;
        while (clazz != Object.class) {
            final ClassReflectionIndex<?> classIndex = index.getClassIndex(clazz);
            Method ret = classIndex.getMethod(returnType, name, namesOf(method.getParameterTypes()));
            if (ret != null) {
                return ret;
            }
            clazz = clazz.getSuperclass();
        }
        throw new DeploymentUnitProcessingException("Could not resolve corresponding " + ejbMethodName + " for home interface method " + method + " on EJB " + ejbName);
    }

    private Method resolveEjbFinderMethod(final Class<?> componentClass, final DeploymentReflectionIndex index, final Method method, final String ejbName) throws DeploymentUnitProcessingException {

        final String name = method.getName().replaceFirst("find", "ejbFind");
        Class<?> clazz = componentClass;
        while (clazz != Object.class) {
            final ClassReflectionIndex<?> classIndex = index.getClassIndex(clazz);
            Collection<Method> methods = classIndex.getMethods(name, method.getParameterTypes());
            if (!methods.isEmpty()) {
                return methods.iterator().next();
            }
            clazz = clazz.getSuperclass();
        }
        throw new DeploymentUnitProcessingException("Could not resolve corresponding ejbFind method for home interface method " + method + " on EJB " + ejbName);
    }

    private Method resolveEjbHomeBusinessMethod(final Class<?> componentClass, final DeploymentReflectionIndex index, final Method method, final String ejbName) throws DeploymentUnitProcessingException {

        final String name = "ejbHome" + Character.toUpperCase(method.getName().charAt(0)) + method.getName().substring(1);
        Class<?> clazz = componentClass;
        while (clazz != Object.class) {
            final ClassReflectionIndex<?> classIndex = index.getClassIndex(clazz);
            Collection<Method> methods = classIndex.getMethods(name, method.getParameterTypes());
            if (!methods.isEmpty()) {
                return methods.iterator().next();
            }
            clazz = clazz.getSuperclass();
        }
        throw new DeploymentUnitProcessingException("Could not resolve corresponding ejbHome method for home interface method " + method + " on EJB " + ejbName);
    }

    private static String[] namesOf(final Class<?>[] types) {
        final String[] strings = new String[types.length];
        for (int i = 0, typesLength = types.length; i < typesLength; i++) {
            strings[i] = types[i].getName();
        }
        return strings;
    }
}
