//: "The contents of this file are subject to the Mozilla Public License
//: Version 1.1 (the "License"); you may not use this file except in
//: compliance with the License. You may obtain a copy of the License at
//: http://www.mozilla.org/MPL/
//:
//: Software distributed under the License is distributed on an "AS IS"
//: basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
//: License for the specific language governing rights and limitations
//: under the License.
//:
//: The Original Code is Guanxi (http://www.guanxi.uhi.ac.uk).
//:
//: The Initial Developer of the Original Code is Alistair Young alistair@codebrane.com
//: All Rights Reserved.
//:

package org.guanxi.sp.engine.service.shibboleth;

import java.io.IOException;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.guanxi.common.definitions.Guanxi;
import org.guanxi.common.definitions.Shibboleth;
import org.guanxi.common.metadata.IdPMetadata;
import org.guanxi.sp.Util;
import org.guanxi.sp.engine.Config;
import org.guanxi.xal.saml2.metadata.GuardRoleDescriptorExtensions;
import org.guanxi.xal.saml_1_0.protocol.ResponseType;
import org.guanxi.xal.saml_2_0.metadata.EntityDescriptorType;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.multiaction.MultiActionController;

/**
 * Shibboleth AuthenticationStatement consumer service. This service accepts an AuthenticationStatement
 * from a Shibboleth Identity Provider and requests attributes for the subject. It then passes those
 * attributes to the appropriate Guard that started the session that resulted in the
 * AuthenticationStatement being sent here.
 * By the time this service reached, the Identity Provider will have been verified.
 *
 * @author Alistair Young alistair@codebrane.com
 * @author Marcin Mielnicki mielniczu@o2.pl - bug fixing
 */
public class AuthConsumerService extends MultiActionController implements ServletContextAware {
  
  /** The view to redirect to if no error occur */
  private String podderView = null;
  /** The view to use to display any errors */
  private String errorView = null;
  /** The variable to use in the error view to display the error */
  private String errorViewDisplayVar = null;
  
  private static Map<HttpSession, AuthConsumerServiceThread> threads;

  public void init() {
    threads = new TreeMap<HttpSession, AuthConsumerServiceThread>(new Comparator<HttpSession>(){
      public int compare(HttpSession one, HttpSession two) {
        return one.getId().compareTo(two.getId());
      }
    });
  } //init

  /**
   * Cleans up when the system shuts down
   */
  public void destroy() {
  } // destroy
  
  /**
   * This is the handler for the initial /shibb/acs page. This receives the 
   * browser after it has visited the IdP and it spawns a thread associated
   * with the collection of attributes. It then redirects the user to the
   * process page which checks the status of the thread and displays a please
   * wait message, or forwards the user, as appropriate.
   * 
   * @param request
   * @param response
   * @throws IOException
   */
  public void acs(HttpServletRequest request, HttpServletResponse response) throws IOException {
    AuthConsumerServiceThread thread;
    HttpSession session;
    String  guardSession, 
            acsURL, aaURL, podderURL, 
            entityID, keystoreFile, keystorePassword, 
            truststoreFile, truststorePassword,
            idpProviderId, idpNameIdentifier;
    ResponseType samlResponse;
    
    session = request.getSession(true);
    
    { // code block to allow temporary variables to fall out of scope once their usefulness has come to an end
      EntityDescriptorType guardEntityDescriptor;
      GuardRoleDescriptorExtensions guardNativeMetadata;
      IdPMetadata idpMetadata;
      Config config;
      
      /* When a Guard initially set up a session with the Engine, it passed its session ID to
      * the Engine's WAYF Location web service. The Guard then passed the session ID to the
      * WAYF/IdP via the target parameter. So now it should come back here and we can
      * identify the Guard that we're working on behalf of.
      */
      guardSession = request.getParameter(Shibboleth.TARGET_FORM_PARAM);
      
      /* When the Engine received the Guard's session, it munged it to an Engine session and
      * associated the Guard session ID with the Guard's ID. So now dereference the Guard's
      * session ID to get its ID and load it's metadata
      */
      guardEntityDescriptor = (EntityDescriptorType)getServletContext().getAttribute(guardSession.replaceAll("GUARD", "ENGINE"));
      guardNativeMetadata   = Util.getGuardNativeMetadata(guardEntityDescriptor);
      
      idpMetadata = (IdPMetadata)request.getAttribute(Config.REQUEST_ATTRIBUTE_IDP_METADATA);
      config      = (Config)getServletContext().getAttribute(Guanxi.CONTEXT_ATTR_ENGINE_CONFIG);
      
      aaURL              = idpMetadata.getAttributeAuthorityURL();
      acsURL             = guardNativeMetadata.getAttributeConsumerServiceURL();
      podderURL          = guardNativeMetadata.getPodderURL();
      entityID           = guardEntityDescriptor.getEntityID();
      keystoreFile       = guardNativeMetadata.getKeystore();
      keystorePassword   = guardNativeMetadata.getKeystorePassword();
      truststoreFile     = config.getTrustStore();
      truststorePassword = config.getTrustStorePassword();
      idpProviderId      = (String)request.getAttribute(Config.REQUEST_ATTRIBUTE_IDP_PROVIDER_ID);
      idpNameIdentifier  = (String)request.getAttribute(Config.REQUEST_ATTRIBUTE_IDP_NAME_IDENTIFIER);
      samlResponse       = (ResponseType)request.getAttribute(Config.REQUEST_ATTRIBUTE_SAML_RESPONSE);
    }
    
    thread = new AuthConsumerServiceThread(this, guardSession, acsURL, aaURL, 
                                       podderURL, entityID, keystoreFile, keystorePassword, 
                                       truststoreFile, truststorePassword, idpProviderId, idpNameIdentifier, 
                                       samlResponse);
    new Thread(thread).start();
    threads.put(session, thread);
    
    response.sendRedirect("process");
  }
  
  /**
   * This checks the status of the thread associated with this request. This will display
   * either a please wait message (with progress bar) or will forward the user to the
   * Podder.
   * 
   * @param request
   * @param response
   * @param session
   * @return
   */
  @SuppressWarnings("unchecked")
  public ModelAndView process(HttpServletRequest request, HttpServletResponse response, HttpSession session) {
    AuthConsumerServiceThread thread;
    
    thread = threads.get(session);
    if ( thread == null ) {
      ModelAndView mAndV;
      
      mAndV = new ModelAndView();
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, "Processing thread cannot be found");
      
      return mAndV;
    }
    if ( thread.isCompleted() ) {
      threads.remove(session); // TODO: Periodic unloading of expired threads?
    }
    return thread.getStatus();
  }

  public String getPodderView() {
    return podderView;
  }

  public void setPodderView(String podderView) {
    this.podderView = podderView;
  }

  public String getErrorView() {
    return errorView;
  }

  public void setErrorView(String errorView) {
    this.errorView = errorView;
  }
  
  public String getErrorViewDisplayVar() {
    return errorViewDisplayVar;
  }

  public void setErrorViewDisplayVar(String errorViewDisplayVar) {
    this.errorViewDisplayVar = errorViewDisplayVar;
  }
}
