/*
 * (C) Copyright 2009-2010 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.opencmis.bindings;

import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

/**
 * Interface defining a login method, used during SOAP authentication.
 * <p>
 * The provider can be redefined by a Nuxeo Framework property named with the
 * full name of this interface.
 */
public interface LoginProvider {

    /**
     * Log in the user given the username and password, and returns a login
     * context.
     *
     * @param username the username
     * @param password the password
     * @return the login context
     * @throws LoginException if the user cannot be logged in
     */
    LoginContext login(String username, String password) throws LoginException;

}