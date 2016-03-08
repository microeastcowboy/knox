/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.gateway;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.gateway.services.DefaultGatewayServices;
import org.apache.hadoop.gateway.services.GatewayServices;
import org.apache.hadoop.gateway.services.ServiceLifecycleException;
import org.apache.hadoop.gateway.services.topology.TopologyService;
import org.apache.hadoop.test.TestUtils;
import org.apache.hadoop.test.mock.MockServer;
import org.apache.http.HttpStatus;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.hamcrest.MatcherAssert;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.StringWriter;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static com.jayway.restassured.RestAssured.given;
import static org.apache.hadoop.test.TestUtils.LOG_ENTER;
import static org.apache.hadoop.test.TestUtils.LOG_EXIT;
import static org.hamcrest.CoreMatchers.*;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

public class AmbariServiceDefinitionTest {

  private static Logger LOG = LoggerFactory.getLogger( AmbariServiceDefinitionTest.class );
  private static Class DAT = AmbariServiceDefinitionTest.class;

  private static GatewayTestConfig config;
  private static DefaultGatewayServices services;
  private static GatewayServer gateway;
  private static int gatewayPort;
  private static String gatewayUrl;
  private static String clusterUrl;
  private static Properties params;
  private static TopologyService topos;
  private static MockServer mockAmbari;

  private static VelocityEngine velocity;
  private static VelocityContext context;

  @BeforeClass
  public static void setupSuite() throws Exception {
    LOG_ENTER();
    setupGateway();
    String topoStr = TestUtils.merge( DAT, "test-topology.xml", params );
    File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
    FileUtils.writeStringToFile( topoFile, topoStr );
    topos.reloadTopologies();
    LOG_EXIT();
  }

  @AfterClass
  public static void cleanupSuite() throws Exception {
    LOG_ENTER();
    gateway.stop();
    FileUtils.deleteQuietly( new File( config.getGatewayHomeDir() ) );
    LOG_EXIT();
  }

  @After
  public void cleanupTest() throws Exception {
    FileUtils.cleanDirectory( new File( config.getGatewayTopologyDir() ) );
    FileUtils.cleanDirectory( new File( config.getGatewayDeploymentDir() ) );
  }

  public static void setupGateway() throws Exception {
    File targetDir = new File( System.getProperty( "user.dir" ), "target" );
    File gatewayDir = new File( targetDir, "gateway-home-" + UUID.randomUUID() );
    gatewayDir.mkdirs();

    config = new GatewayTestConfig();
    config.setGatewayHomeDir( gatewayDir.getAbsolutePath() );

    URL svcsFileUrl = TestUtils.getResourceUrl( DAT, "test-svcs/readme.txt" );
    File svcsFile = new File( svcsFileUrl.getFile() );
    File svcsDir = svcsFile.getParentFile();
    config.setGatewayServicesDir( svcsDir.getAbsolutePath() );

    String pathToStacksSource = "gateway-service-definitions/src/main/resources/services";
    File stacksSourceDir = new File( targetDir.getParent(), pathToStacksSource);
    if (!stacksSourceDir.exists()) {
      stacksSourceDir = new File( targetDir.getParentFile().getParent(), pathToStacksSource);
    }
    if (stacksSourceDir.exists()) {
      FileUtils.copyDirectoryToDirectory(stacksSourceDir, svcsDir);
    }

    File topoDir = new File( config.getGatewayTopologyDir() );
    topoDir.mkdirs();

    File deployDir = new File( config.getGatewayDeploymentDir() );
    deployDir.mkdirs();

    setupMockServers();
    startGatewayServer();
  }

  public static void setupMockServers() throws Exception {
    mockAmbari = new MockServer( "AMBARI", true );
  }

  public static void startGatewayServer() throws Exception {
    services = new DefaultGatewayServices();
    Map<String,String> options = new HashMap<String,String>();
    options.put( "persist-master", "false" );
    options.put( "master", "password" );
    try {
      services.init( config, options );
    } catch ( ServiceLifecycleException e ) {
      e.printStackTrace(); // I18N not required.
    }
    topos = services.getService(GatewayServices.TOPOLOGY_SERVICE);

    gateway = GatewayServer.startGateway( config, services );
    MatcherAssert.assertThat( "Failed to start gateway.", gateway, notNullValue() );

    gatewayPort = gateway.getAddresses()[0].getPort();
    gatewayUrl = "http://localhost:" + gatewayPort + "/" + config.getGatewayPath();
    clusterUrl = gatewayUrl + "/test-topology";

    LOG.info( "Gateway port = " + gateway.getAddresses()[ 0 ].getPort() );

    params = new Properties();
    params.put( "AMBARI_URL", "http://localhost:" + mockAmbari.getPort() );

    velocity = new VelocityEngine();
    velocity.setProperty( RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS, "org.apache.velocity.runtime.log.NullLogSystem" );
    velocity.setProperty( RuntimeConstants.RESOURCE_LOADER, "classpath" );
    velocity.setProperty( "classpath.resource.loader.class", ClasspathResourceLoader.class.getName() );
    velocity.init();

    context = new VelocityContext();
    context.put( "cluster_url", clusterUrl );

  }

  @Test( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void clusters() throws Exception {
    LOG_ENTER();

    String username = "guest";
    String password = "guest-password";
    String serviceUrl =  clusterUrl + "/ambari/api/v1/clusters";

    mockAmbari.expect()
        .method( "GET" )
        .pathInfo( "/api/v1/clusters" )
        .respond()
        .status( HttpStatus.SC_OK )
        .content( TestUtils.getResourceStream( DAT, "clusters-response.json" ) )
        .contentType( "text/plain" );

    String body = given()
//        .log().all()
        .auth().preemptive().basic( username, password )
        .expect()
//        .log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( "text/plain" )
        .when().get( serviceUrl ).asString();


    String name = TestUtils.getResourceName( this.getClass(), "clusters-response-expected.json" );
    Template template = velocity.getTemplate( name );
    StringWriter sw = new StringWriter();
    template.merge( context, sw );
    String expected = sw.toString();

    MatcherAssert.assertThat(body, sameJSONAs(expected));
    LOG_EXIT();
  }

  @Test( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void historyServer() throws Exception {
    LOG_ENTER();

    String username = "guest";
    String password = "guest-password";
    String serviceUrl =  clusterUrl + "/ambari/api/v1/clusters/test/hosts/c6401.ambari.apache.org/host_components/HISTORYSERVER";

    mockAmbari.expect()
        .method( "GET" )
        .pathInfo( "/api/v1/clusters/test/hosts/c6401.ambari.apache.org/host_components/HISTORYSERVER" )
        .respond()
        .status( HttpStatus.SC_OK )
        .content( TestUtils.getResourceStream( DAT, "history-server-response.json" ) )
        .contentType( "text/plain" );

    String body = given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        .contentType( "text/plain" )
        .when().get( serviceUrl ).asString();


    String name = TestUtils.getResourceName( this.getClass(), "history-server-response-expected.json" );
    Template template = velocity.getTemplate( name );
    StringWriter sw = new StringWriter();
    template.merge( context, sw );
    String expected = sw.toString();

    MatcherAssert.assertThat(body, sameJSONAs(expected));
    LOG_EXIT();
  }

}
