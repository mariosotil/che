/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.api.workspace.server;

import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.che.api.core.BadRequestException;
import org.eclipse.che.api.core.rest.HttpJsonRequest;
import org.eclipse.che.api.core.rest.HttpJsonRequestFactory;
import org.eclipse.che.api.core.rest.HttpJsonResponse;
import org.eclipse.che.api.machine.shared.dto.recipe.RecipeDescriptor;
import org.eclipse.che.commons.test.mockito.answer.SelfReturningAnswer;
import org.eclipse.che.dto.server.DtoFactory;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.testng.MockitoTestNGListener;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;

import static org.eclipse.che.api.core.util.LinksHelper.createLink;
import static org.eclipse.che.api.machine.shared.Constants.LINK_REL_GET_RECIPE_SCRIPT;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Tests of {@link WorkspaceConfigAdapter}.
 *
 * @author Yevhenii Voevodin
 */
@Listeners(MockitoTestNGListener.class)
public class WorkspaceConfigAdapterTest {

    private static final String VALID_CONFIG_FILENAME    = "old_workspace_config_format.json";
    private static final String INVALID_CONFIGS_DIR_NAME = "invalid_configs";

    @Mock
    private HttpJsonRequestFactory httpReqFactory;

    @Mock
    private HttpJsonResponse response;

    @InjectMocks
    private WorkspaceConfigAdapter configAdapter;

    @BeforeMethod
    private void setUp() throws Exception {
        configAdapter = new WorkspaceConfigAdapter(httpReqFactory);
        final HttpJsonRequest request = mock(HttpJsonRequest.class, new SelfReturningAnswer());
        when(httpReqFactory.fromUrl(any())).thenReturn(request);
        when(request.request()).thenReturn(response);
        // for the 'site' machine a new recipe should be created
        final RecipeDescriptor rd = DtoFactory.newDto(RecipeDescriptor.class);
        rd.getLinks().add(createLink("GET", "https://test/test_recipe", LINK_REL_GET_RECIPE_SCRIPT));
        when(response.asDto(any())).thenReturn(rd);
    }

    @Test
    public void testWorkspaceConfigAdaptation() throws Exception {
        final String content = loadContent(VALID_CONFIG_FILENAME);
        final JsonObject newConfig = configAdapter.adapt(new JsonParser().parse(content).getAsJsonObject());

        // The type of environments must be changed from array to map
        assertTrue(newConfig.has("environments"), "contains environments object");
        assertTrue(newConfig.get("environments").isJsonObject(), "environments is json object");

        // Environment must be moved out of the environment object
        final JsonObject environmentsObj = newConfig.get("environments").getAsJsonObject();
        assertTrue(environmentsObj.has("dev-env"), "'dev-env' is present in environments list");
        assertTrue(environmentsObj.get("dev-env").isJsonObject(), "'dev-env' is json object");

        final JsonObject environmentObj = environmentsObj.get("dev-env").getAsJsonObject();
        // 'machineConfigs' -> 'machines'
        assertTrue(environmentObj.has("machines"), "'machines' are present in environment object");
        assertTrue(environmentObj.get("machines").isJsonObject(), "'machines' is json object");
        final JsonObject machinesObj = environmentObj.get("machines").getAsJsonObject();
        assertEquals(machinesObj.entrySet().size(), 3, "machines size");

        // check 'dev' machine
        assertTrue(machinesObj.has("dev"), "'machines' contains machine with name 'dev-machine'");
        assertTrue(machinesObj.get("dev").isJsonObject(), "dev machine is json object");
        final JsonObject devMachineObj = machinesObj.get("dev").getAsJsonObject();
        assertTrue(devMachineObj.has("servers"), "dev machine contains servers field");
        assertTrue(devMachineObj.get("servers").isJsonObject(), "dev machine servers is json object");
        final JsonObject devMachineServersObj = devMachineObj.get("servers").getAsJsonObject();
        assertTrue(devMachineServersObj.has("ref"), "contains servers with reference 'ref'");
        assertTrue(devMachineServersObj.get("ref").isJsonObject(), "server is json object");
        final JsonObject devMachineServerObj = devMachineServersObj.get("ref").getAsJsonObject();
        assertEquals(devMachineServerObj.get("port").getAsString(), "9090/udp");
        assertEquals(devMachineServerObj.get("protocol").getAsString(), "protocol");
        assertTrue(devMachineObj.has("agents"), "dev machine has agents");

        // check 'db' machine
        assertTrue(machinesObj.has("db"), "'machines' contains machine with name 'db'");
        assertTrue(machinesObj.get("db").isJsonObject(), "db machine is json object");
        final JsonObject dbMachineObj = machinesObj.get("db").getAsJsonObject();
        assertTrue(dbMachineObj.has("servers"), "db machine contains servers field");
        assertTrue(dbMachineObj.get("servers").isJsonObject(), "db machine servers is json object");
        final JsonObject dbMachineServersObj = dbMachineObj.get("servers").getAsJsonObject();
        assertTrue(dbMachineServersObj.has("ref"), "contains servers with reference 'ref'");
        assertTrue(dbMachineServersObj.get("ref").isJsonObject(), "server is json object");
        final JsonObject dbMachineServer = dbMachineServersObj.get("ref").getAsJsonObject();
        assertEquals(dbMachineServer.get("port").getAsString(), "3311/tcp");
        assertEquals(dbMachineServer.get("protocol").getAsString(), "protocol");

        // check 'site' machine
        assertTrue(machinesObj.has("site"), "'machines' contains machine with name 'site'");
        assertTrue(machinesObj.get("site").isJsonObject(), "site machine is json object");

        // check environment recipe
        assertTrue(environmentObj.has("recipe"), "environment contains recipe");
        assertTrue(environmentObj.get("recipe").isJsonObject(), "environment recipe is json object");
        final JsonObject recipeObj = environmentObj.get("recipe").getAsJsonObject();
        assertEquals(recipeObj.get("type").getAsString(), "compose");
        assertEquals(recipeObj.get("contentType").getAsString(), "application/x-yaml");
        assertEquals(recipeObj.get("content").getAsString(), "services:\n" +
                                                             "  dev:\n" +
                                                             "    build:\n" +
                                                             "      context: https://somewhere/Dockerfile\n" +
                                                             "    mem_limit: 2147483648\n" +
                                                             "    environment:\n" +
                                                             "    - env1=value1\n" +
                                                             "    - env2=value2\n" +
                                                             "  db:\n" +
                                                             "    image: codenvy/ubuntu_jdk8\n" +
                                                             "    mem_limit: 2147483648\n" +
                                                             "  site:\n" +
                                                             "    build:\n" +
                                                             "      context: https://test/test_recipe\n" +
                                                             "    mem_limit: 1073741824\n");
    }

    @Test(expectedExceptions = BadRequestException.class, dataProvider = "invalidConfigs")
    public void testNotValidWorkspaceConfigAdaptations(String filename) throws Exception {
        final String content = loadContent(INVALID_CONFIGS_DIR_NAME + File.separatorChar + filename);

        new WorkspaceConfigAdapter(httpReqFactory).adapt(new JsonParser().parse(content).getAsJsonObject());
    }

    @DataProvider
    public static Object[][] invalidConfigs() throws Exception {
        final URL dir = Thread.currentThread()
                              .getContextClassLoader()
                              .getResource(INVALID_CONFIGS_DIR_NAME);
        assertNotNull(dir);
        final File[] files = new File(dir.toURI()).listFiles();
        assertNotNull(files);
        final Object[][] result = new Object[files.length][1];
        for (int i = 0; i < files.length; i++) {
            result[i][0] = files[i].getName();
        }
        return result;
    }

    private static String loadContent(String filename) throws IOException {
        try (Reader r = new InputStreamReader(Thread.currentThread()
                                                    .getContextClassLoader()
                                                    .getResourceAsStream(filename))) {
            return CharStreams.toString(r);
        }
    }
}
