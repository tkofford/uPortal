package org.jasig.portal.portlets.portletadmin;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.portlet.PortletRequest;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pluto.OptionalContainerServices;
import org.apache.pluto.PortletContainerException;
import org.apache.pluto.descriptors.portlet.PortletDD;
import org.apache.pluto.descriptors.portlet.SupportsDD;
import org.apache.pluto.internal.impl.PortletContextImpl;
import org.apache.pluto.spi.optional.PortletRegistryService;
import org.jasig.portal.ChannelCategory;
import org.jasig.portal.IChannelRegistryStore;
import org.jasig.portal.ResourceMissingException;
import org.jasig.portal.channel.IChannelDefinition;
import org.jasig.portal.channel.IChannelPublishingService;
import org.jasig.portal.channel.IChannelType;
import org.jasig.portal.channels.portlet.IPortletAdaptor;
import org.jasig.portal.groups.GroupsException;
import org.jasig.portal.groups.IEntityGroup;
import org.jasig.portal.groups.IGroupMember;
import org.jasig.portal.portlet.dao.jpa.PortletPreferenceImpl;
import org.jasig.portal.portlet.om.IPortletPreference;
import org.jasig.portal.portlets.Attribute;
import org.jasig.portal.portlets.portletadmin.xmlsupport.CPDParameter;
import org.jasig.portal.portlets.portletadmin.xmlsupport.CPDPreference;
import org.jasig.portal.portlets.portletadmin.xmlsupport.CPDStep;
import org.jasig.portal.portlets.portletadmin.xmlsupport.ChannelPublishingDefinition;
import org.jasig.portal.security.IAuthorizationPrincipal;
import org.jasig.portal.security.IPermissionManager;
import org.jasig.portal.security.IPerson;
import org.jasig.portal.services.AuthorizationService;
import org.jasig.portal.services.GroupService;
import org.jasig.portal.utils.ResourceLoader;
import org.w3c.dom.Document;

import com.thoughtworks.xstream.XStream;

/**
 * Helper methods for the portlet administration workflow.
 * 
 * @author Jen Bourey, jbourey@unicon.net
 * @revision $Revision$
 */
public class PortletAdministrationHelper {

	private Log log = LogFactory.getLog(PortletAdministrationHelper.class);
	private IChannelRegistryStore channelRegistryStore;
	
	/**
	 * Set the channel registry store
	 * 
	 * @param channelRegistryStore
	 */
	public void setChannelRegistryStore(IChannelRegistryStore channelRegistryStore) {
		this.channelRegistryStore = channelRegistryStore;
	}

	private OptionalContainerServices optionalContainerServices;

	public void setOptionalContainerServices(
			OptionalContainerServices optionalContainerServices) {
		this.optionalContainerServices = optionalContainerServices;
	}
	
	private IChannelPublishingService channelPublishingService;	

	public void setChannelPublishingService(
			IChannelPublishingService channelPublishingService) {
		this.channelPublishingService = channelPublishingService;
	}
	
    private Map<Serializable, ChannelPublishingDefinition> cpdCache;

    /**
     * Cache to use for parsed CPDs.
     * 
     * @param cpdCache
     */
	public void setCpdCache(Map<Serializable, ChannelPublishingDefinition> cpdCache) {
		this.cpdCache = cpdCache;
	}

	/**
	 * Construct a new ChannelDefinitionForm for the given IChannelDefinition id.
	 * If a ChannelDefinition matching this ID already exists, the form will
	 * be pre-populated with the ChannelDefinition's current configuration.  If
	 * the ChannelDefinition does not yet exist, a new default form will be
	 * created.
	 * 
	 * @param chanId
	 * @return
	 */
	public ChannelDefinitionForm getChannelDefinitionForm(int chanId) {
		
		IChannelDefinition def = channelRegistryStore.getChannelDefinition(chanId);
		if (def == null) {
			// if no IChannelDefinition is found, create a new one
			def = channelRegistryStore.newChannelDefinition();
		}
		
		// create the new form
		ChannelDefinitionForm form = new ChannelDefinitionForm(def);
		form.setId(def.getId());
		
		// if this is not a new channel, set the category and permissions
        if (form.getId() > 0) {
        	ChannelCategory[] categories = channelRegistryStore.getParentCategories(def);
        	for (ChannelCategory cat : categories) {
        		form.addCategory(cat.getId());
        	}

			try {
                IPermissionManager pm = AuthorizationService.instance().newPermissionManager(IChannelPublishingService.FRAMEWORK_OWNER);
                IAuthorizationPrincipal[] prins = pm.getAuthorizedPrincipals(IChannelPublishingService.SUBSCRIBER_ACTIVITY,
                        "CHAN_ID." + String.valueOf(form.getId()));
                for (int mp = 0; mp < prins.length; mp++) {
                    form.addGroup(AuthorizationService.instance().getGroupMember(prins[mp]).getKey());
                }
			} catch (GroupsException e) {
				e.printStackTrace();
			}
		} else {
			// temporarily adding in a default group and category
			form.addGroup("local.0");
			form.addCategory("local.11");
		}

		return form;
	}
	
	/**
	 * Persist a new or edited ChannelDefinition.
	 * 
	 * @param form
	 * @param publisher
	 */
	public void savePortletRegistration(ChannelDefinitionForm form,
			IPerson publisher) throws Exception {
		
		// create the group array from the form's group list
		IGroupMember[] groupMembers = new IGroupMember[form.getGroups().size()];
		for (int i = 0; i < groupMembers.length; i++) {
			groupMembers[i] = GroupService.getGroupMember(form.getGroups().get(i),
					IEntityGroup.class);
		}
		
		ChannelCategory[] categories = new ChannelCategory[form.getCategories().size()];
		for (ListIterator<String> iter = form.getCategories().listIterator(); iter.hasNext();) {
			String id = iter.next();
			String iCatID = id.startsWith("cat") ? id.substring(3) : id;
			categories[iter.previousIndex()] = channelRegistryStore
					.getChannelCategory(iCatID);
		}

		// create the category array from the form's category list
		String[] categoryIDs = form.getCategories().toArray(new String[form.getCategories().size()]);

	    IChannelDefinition channelDef = channelRegistryStore.getChannelDefinition(form.getId());
	    if (channelDef == null) {
	    	channelDef = channelRegistryStore.newChannelDefinition();
	    }
	    channelDef.setDescription(form.getDescription());
	    channelDef.setEditable(form.isEditable());
	    channelDef.setFName(form.getFname());
	    channelDef.setHasAbout(form.isHasAbout());
	    channelDef.setHasHelp(form.isHasHelp());
	    channelDef.setIsSecure(form.isSecure());
	    channelDef.setJavaClass(form.getJavaClass());
	    channelDef.setName(form.getName());
	    channelDef.setTimeout(form.getTimeout());
	    channelDef.setTitle(form.getTitle());
	    channelDef.setTypeId(form.getTypeId());
	    
	    // add channel parameters
		List<IPortletPreference> portletPreferences = new ArrayList<IPortletPreference>();
		for (String key : form.getParameters().keySet()) {
			String value = form.getParameters().get(key).getValue();
			if (!StringUtils.isBlank(value)) {
				boolean override = false;
				if (form.getParameterOverrides().containsKey(key)) {
					override = form.getParameterOverrides().get(key).getValue();
				}
				if (key.startsWith("PORTLET.")) {
					portletPreferences.add(new PortletPreferenceImpl(key, !override, new String[]{value}));
				} else {
					channelDef.addParameter(key, value, override);
				}
			}
		}
		
		for (String key : form.getPortletPreferences().keySet()) {
			List<String> prefValues = form.getPortletPreferences().get(key).getValue();
			if (prefValues != null && prefValues.size() > 0) {
				String[] values = prefValues.toArray(new String[prefValues.size()]);
				boolean readOnly = true;
				if (form.getPortletPreferencesOverrides().containsKey(key)) {
					readOnly = !form.getPortletPreferencesOverrides().get(key).getValue();
				}
				portletPreferences.add(new PortletPreferenceImpl(key, readOnly, values));
			}
		}
		channelDef.replacePortletPreference(portletPreferences);
	    
	    channelPublishingService.saveChannelDefinition(channelDef, publisher, categories, groupMembers);

	}
	
	/**
	 * Delete the portlet with the given channel ID.
	 * 
	 * @param channelID the channel ID
	 * @param person the person removing the channel
	 */
	public void removePortletRegistration(int channelId, IPerson person) {
		IChannelDefinition channelDef = channelRegistryStore.getChannelDefinition(channelId);
		channelPublishingService.removeChannelDefinition(channelDef, person);
	}
	
	/**
	 * Return a ChannelPublishingDocument for a specified channel type id.
	 * 
	 * @param channelTypeId
	 * @return
	 */
	public ChannelPublishingDefinition getChannelType(int channelTypeId) {
		
		// attempt to retrieve the CPD from the cache
		if (this.cpdCache.containsKey(channelTypeId)) {
			return this.cpdCache.get(channelTypeId);
		}
		
		// if the CPD is not already in the cache, determine the CPD URI
		String cpdUri;
		if (channelTypeId >= 0) {
			IChannelType type = channelRegistryStore.getChannelType(channelTypeId);
			cpdUri = type.getCpdUri();
		} else {
			cpdUri = "org/jasig/portal/portlets/portletadmin/CustomChannel.cpd";
		}
		
		// read in the CPD
		InputStream inputStream = null;
		try {
			inputStream = ResourceLoader.getResourceAsStream(PortletAdministrationHelper.class, cpdUri);
		} catch (ResourceMissingException e) {
			log.error("Failed to locate CPD for channel type " + channelTypeId, e);
		} catch (IOException e) {
			log.error("Failed to load CPD for channel type " + channelTypeId, e);
		}
		
		// parse the CPD
		XStream stream = new XStream();
		stream.processAnnotations(ChannelPublishingDefinition.class);
		ChannelPublishingDefinition def = (ChannelPublishingDefinition) stream.fromXML(inputStream);
		
		// add the CPD to the cache and return it
		this.cpdCache.put(channelTypeId, def);
		return def;
	}
	
	/**
	 * Get a list of the key names of the currently-set arbitrary portlet
	 * preferences.
	 * 
	 * @param form
	 * @param cpd
	 * @return
	 */
	public Set<String> getArbitraryPortletPreferenceNames(ChannelDefinitionForm form) {
		// set default values for all channel parameters
		ChannelPublishingDefinition cpd = getChannelType(form.getTypeId());
		Set<String> currentPrefs = new HashSet<String>();
		currentPrefs.addAll(form.getPortletPreferences().keySet());
		for (CPDStep step : cpd.getParams().getSteps()) {
			if (step.getPreferences() != null) {
				for (CPDPreference pref : step.getPreferences()) {
					currentPrefs.remove(pref.getName());
				}
			}
			if (step.getParameters() != null) {
				for (CPDParameter param : step.getParameters()) {
					if (param.getName().startsWith("PORTLET.")) {
						currentPrefs.remove(param.getName().replace("PORTLET.", ""));
					}
				}
			}
		}
		return currentPrefs;
	}
	
	public void cleanOptions(ChannelDefinitionForm form, PortletRequest request) {
		Set<String> preferenceNames = new HashSet<String>();
		Set<String> parameterNames = new HashSet<String>();
		for (Enumeration e = request.getParameterNames(); e.hasMoreElements();) {
			String name = (String) e.nextElement();
			if (name.startsWith("portletPreferences[")) {
				preferenceNames.add(name.split("\'")[1]);
			} else if (name.startsWith("parameters[")) {
				parameterNames.add(name.split("\'")[1]);
			}
		}
		
		Set<String> keys = new HashSet<String>();
		keys.addAll(form.getPortletPreferences().keySet());
		for (String key : keys) {
			if (!preferenceNames.contains(key)) {
				form.getPortletPreferences().remove(key);
				form.getPortletPreferencesOverrides().remove(key);
			} else if (form.getPortletPreferences().get(key) == null) {
				form.getPortletPreferences().remove(key);
				form.getPortletPreferencesOverrides().remove(key);
			} else {
				List<String> values = form.getPortletPreferences().get(key).getValue();
				for (ListIterator<String> iter = values.listIterator(); iter.hasNext();) {
					String value = iter.next();
					if (StringUtils.isEmpty(value)) {
						iter.remove();
					}
				}
				if (values.size() == 0) {
					form.getPortletPreferences().remove(key);
					form.getPortletPreferencesOverrides().remove(key);
				}
			}
		}
		
		keys = new HashSet<String>();
		keys.addAll(form.getParameters().keySet());
		for (String key : keys) {
			if (!parameterNames.contains(key)) {
				form.getParameters().remove(key);
				form.getParameterOverrides().remove(key);
			} else if (form.getParameters().get(key) == null || StringUtils.isBlank(form.getParameters().get(key).getValue())) {
				form.getParameters().remove(key);
				form.getParameterOverrides().remove(key);
			}
		}
	}
	
	/**
	 * Retreive the list of portlet application contexts currently available in
	 * this portlet container.
	 * 
	 * @return list of portlet context
	 */
	@SuppressWarnings("unchecked")
	public List<PortletContextImpl> getPortletApplications() {
		final PortletRegistryService portletRegistryService = optionalContainerServices.getPortletRegistryService();
		List<PortletContextImpl> contexts = new ArrayList<PortletContextImpl>();
		for (Iterator iter = portletRegistryService.getRegisteredPortletApplications(); iter.hasNext();) {
			PortletContextImpl context = (PortletContextImpl) iter.next();
			contexts.add(context);
		}
		return contexts;
	}
	
	/**
	 * Get a portlet descriptor matching the current channel definition form.
	 * If the current form does not represent a portlet, the application or 
	 * portlet name fields are blank, or the portlet description cannot be 
	 * retrieved, the method will return <code>null</code>.
	 * 
	 * @param form
	 * @return
	 */
	public PortletDD getPortletDescriptor(ChannelDefinitionForm form) {
		if (!form.isPortlet() || !form.getParameters().containsKey("portletApplicationId") || !form.getParameters().containsKey("portletName")) {
			return null;
		}
		
		final String application = form.getParameters().get(IPortletAdaptor.CHANNEL_PARAM__PORTLET_APPLICATION_ID).getValue();
		final String portlet = form.getParameters().get(IPortletAdaptor.CHANNEL_PARAM__PORTLET_NAME).getValue();
		if (StringUtils.isBlank(application) || StringUtils.isBlank(portlet)) {
			return null;
		}
		
		final PortletRegistryService portletRegistryService = optionalContainerServices.getPortletRegistryService();
		try {
			PortletDD portletDD = portletRegistryService.getPortletDescriptor(application, portlet);
			return portletDD;
		} catch (PortletContainerException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	/**
	 * Pre-populate a ChannelDefinitionForm with portlet-specific information
	 * using the supplied portlet descriptor.
	 * 
	 * @param application
	 * @param portlet
	 * @param form
	 */
	public void prepopulatePortlet(String application, String portlet, ChannelDefinitionForm form) {
		final PortletRegistryService portletRegistryService = optionalContainerServices.getPortletRegistryService();
		try {
			PortletDD portletDD = portletRegistryService.getPortletDescriptor(application, portlet);
			form.setTitle(portletDD.getPortletName());
			form.setName(portletDD.getPortletName());
			form.getParameters().put(IPortletAdaptor.CHANNEL_PARAM__PORTLET_APPLICATION_ID, new Attribute(application));
			form.getParameters().put(IPortletAdaptor.CHANNEL_PARAM__PORTLET_NAME, new Attribute(portletDD.getPortletName()));
			for (Object obj : portletDD.getSupports()) {
				SupportsDD supports = (SupportsDD) obj;
				for (Object mode : supports.getPortletModes()) {
					if ("edit".equals(mode)) {
						form.setEditable(true);
					} else if ("help".equals(mode)) {
						form.setHasHelp(true);
					}
				}
			}
		} catch (PortletContainerException e) {
			e.printStackTrace();
		}
	}
		
}