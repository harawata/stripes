/* Copyright 2005-2006 Tim Fennell
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sourceforge.stripes.controller;

import net.sourceforge.stripes.action.ActionBean;
import net.sourceforge.stripes.util.Log;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>A FlashScope is an object that can be used to store objects and make them available as
 * request parameters during this request cycle and the next one.  It is extremely useful
 * when implementing the redirect-after-post pattern in which an ActionBean receives a POST,
 * does some processing and then redirects to a JSP to display the outcome. FlashScopes make
 * <i>temporary</i> use of session to store themselves briefly between two requests.</p>
 *
 * <p>In general, use of the FlashScope should be intermediated by the
 * {@link net.sourceforge.stripes.action.ActionBeanContext}, making it transparent to the
 * rest of the application.  Any object that is put into a FlashScope will be immediately
 * exposed in the current request as a request attribute, and under certain conditions will
 * also be exposed in the subsequent request as a request attribute.</p>
 *
 * <p>To make values available to the subsequent request a parameter must be included in
 * the redirect URL that identifies the flash scope to use (this avoids collisions where two
 * concurrent requests in the same session might otherwise cause problems for one another).
 * The Stripes {@link net.sourceforge.stripes.action.RedirectResolution} will automatically
 * insert this parameter into the URL when a flash scope is present.  Should you wish to issue
 * redirects using a different mechanism you will need to add the parameter using code
 * similar to the following:</p>
 *
 *<pre>
 *FlashScope flash = FlashScope.getCurrent(request, false);
 *if (flash != null) {
 *    url.addParameter(StripesConstants.URL_KEY_FLASH_SCOPE_ID, flash.key());
 *}
 *</pre>
 *
 * <p>The lifecycle of a FlashScope is managed is conjunction with the {@link StripesFilter}.
 * FlashScopes are manufactured using lazy instantiation when
 * {@code FlashScope.getCurrent(request, true)} is called.  When a request is completed, the
 * StripesFilter notifies the current FlashScope that the request is over, which causes it
 * to record the time when the request terminated.  On the subsequent request, if the flash
 * scope is referenced by a URL parameter, then it is removed from session and it's contents
 * are pushed into request attributes for the current request.</p>
 *
 * <p>To ensure that orphaned FlashScopes do not consume increasing amounts of HttpSession memory,
 * the StripesFilter, after each request, checks to see if any FlashScopes have been present
 * longer than a pre-determined length of time.  Since the timer starts when a request completes,
 * and FlashScopes are only meant to live from the end of one request to the beginning of a
 * subsequent request this value is set quite low.</p>
 *
 * @author Tim Fennell
 * @since Stripes 1.2
 */
public class FlashScope extends HashMap<String,Object> implements Serializable {
    private static final Log log = Log.getInstance(FlashScope.class);
    private long startTime;
    private HttpServletRequest request;

    /**
     * Protected constructor to prevent random creation of FlashScopes. Uses the request
     * to generate a key under which the flash scope will be stored, and can be identified
     * by later.
     *
     * @param request the request for which this flash scope will be used.
     */
    protected FlashScope(HttpServletRequest request) {
        this.request = request;
    }

    /**
     * Returns the key used to store this flash scope in the colleciton of flash scopes.
     */
    public Integer key() {
        return this.request.hashCode();
    }

    /**
     * <p>Used by the StripesFilter to notify the flash scope that the request for which
     * it is used has been completed. The FlashScope uses this notification to start a
     * timer, and also to null out it's reference to the request so that it can be
     * garbage collected.</p>
     *
     * <p>The timer is used to determine if a flash scope has been orphaned (i.e. the subsequent
     * request was not made) after a period of time, so that it can be removed from session.</p>
     */
    public void requestComplete() {
        this.startTime = System.currentTimeMillis();
        this.request = null;
    }

    /**
     * Returns the time in seconds since the request that generated this flash scope
     * completed.  Will return 0 if this flash scope has not yet started to age.
     */
    public long age() {
        if (startTime == 0) {
            return 0;
        }
        else {
            return (System.currentTimeMillis() - this.startTime) / 1000;
        }
    }

    /**
     * Stores the provided value <b>both</b> in the flash scope a under the specified name, and
     * in a request attribute with the specified name. Allows flash scope attributes to be
     * accessed seamlessly as request attributes during both the current request and the
     * subsequent request.
     *
     * @param name the name of the attribute to add to flash scope
     * @param value the value to be added
     * @return the previous object stored with the same name (possibly null)
     */
    @Override
    public Object put(String name, Object value) {
        this.request.setAttribute(name, value);
        return super.put(name, value);
    }

    /**
     * Stores an ActionBean into the flash scope.  Additional checking is performed to see
     * if the ActionBean is the currently resolved (main) ActionBean for the request. The
     * result is that on the next request the ActionBean will appear in the request as if
     * it was created on that request.
     *
     * @param bean an ActionBean that should be present in the next request
     */
    public void put(ActionBean bean) {
        String binding = StripesFilter.getConfiguration()
                                      .getActionResolver().getUrlBinding(bean.getClass());
        super.put(binding, bean);

        ActionBean main = (ActionBean) request.getAttribute(StripesConstants.REQ_ATTR_ACTION_BEAN);
        if (main != null && main.equals(bean)) {
            super.put(StripesConstants.REQ_ATTR_ACTION_BEAN, bean);
        }
    }

    /**
     * Gets the collection of all flash scopes present in the current session.
     * @param req the current request, needed to get access to the session
     * @return a collection of flash scopes.  Will return an empty collection if there are
     *         no flash scopes present.
     */
    public static Collection<FlashScope> getAllFlashScopes(HttpServletRequest req) {
        Map<Integer,FlashScope> scopes = getContainer(req, false);

        if (scopes == null) {
            return Collections.emptySet();
        }
        else {
            return scopes.values();
        }
    }

    /**
     * <p>Fetch the flash scope that was populated during the previous request, if one exists.
     * This is only really intended for use by the StripesFilter and things which extend it,
     * in order to grab a flash scope for a previous request and empty it's contents into request
     * attributes.</p>
     *
     * <p>NOTE: calling this method has the side-affect of removing the flash scope from
     * the set of managed flash scopes!</p>
     *
     * @param req the current request
     * @return a FlashScope if one exists with the key provided.
     */
    public static FlashScope getPrevious(HttpServletRequest req) {
        String keyString = req.getParameter(StripesConstants.URL_KEY_FLASH_SCOPE_ID);

        if (keyString == null) {
            return null;
        }
        else {
            Integer id = new Integer(keyString);
            Map<Integer,FlashScope> scopes = getContainer(req, false);
            return (scopes == null) ? null : scopes.remove(id);
        }
    }

    /**
     * Gets the current flash scope into which items can be stored temporarily.
     *
     * @param req the current request
     * @param create if true then the FlashScope will be created when it does not exist already
     * @return the current FlashScope, or null if it does not exist and create is false
     */
    public static FlashScope getCurrent(HttpServletRequest req, boolean create) {
        Map<Integer,FlashScope> scopes = getContainer(req, create);

        if (scopes == null) {
            return null;
        }
        else {
            FlashScope scope = scopes.get(req.hashCode());
            if (scope == null && create) {
                scope = new FlashScope(req);
                scopes.put(req.hashCode(), scope);
            }

            return scope;
        }
    }

    /**
     * Internal helper method to retreive (and selectively create) the container for all
     * the flash scopes.  Will return null if the container does not exist and <i>create</i> is
     * false.  Will also return null if the current session has been invalidated, regardless
     * of the value of <i>create</i>.
     *
     * @param req the current request
     * @param create if true, create the container when it doesn't exist.
     * @return a Map of integer keys to FlashScope objects
     */
    private static Map<Integer,FlashScope> getContainer(HttpServletRequest req, boolean create) {
        try {
            HttpSession session =  req.getSession(create);
            Map<Integer,FlashScope> scopes = null;
            if (session != null) {
                 scopes = (Map<Integer,FlashScope>)
                        session.getAttribute(StripesConstants.REQ_ATTR_FLASH_SCOPE_LOCATION);

                if (scopes == null && create) {
                    scopes = new HashMap<Integer,FlashScope>();
                    req.getSession().setAttribute(StripesConstants.REQ_ATTR_FLASH_SCOPE_LOCATION, scopes);
                }
            }

            return scopes;
        }
        catch (IllegalStateException ise) {
            // If the session has been invalidated we'll get this exception, but there's no
            // way to know this without try and getting the exception :(
            log.warn("An IllegalStateException got thrown trying to create a flash scope. ",
                     "This happens when add something to flash scope for the first time ",
                     "causes creation of the HttpSession, but for some other reason the ",
                     "response is already committed!");
            return null;
        }
    }
}
