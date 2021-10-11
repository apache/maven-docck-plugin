package org.apache.maven.plugin.docck;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;
import org.apache.maven.model.IssueManagement;
import org.apache.maven.model.License;
import org.apache.maven.model.Organization;
import org.apache.maven.model.Prerequisites;
import org.apache.maven.model.Scm;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.docck.reports.DocumentationReport;
import org.apache.maven.plugin.docck.reports.DocumentationReporter;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;

/**
 * Performs the heavy lifting for documentation checks. This is designed to be
 * reused for other types of projects, too.
 *
 * @author jdcasey
 */
public abstract class AbstractCheckDocumentationMojo
    extends AbstractMojo
{
    private static final int HTTP_STATUS_200 = 200;

    /**
     */
    @Parameter( property = "reactorProjects", readonly = true, required = true )
    private List<MavenProject> reactorProjects;

    /**
     * An optional location where the results will be written to. If this is
     * not specified the results will be written to the console.
     */
    @Parameter( property = "output" )
    private File output;

    /**
     * Directory where the site source for the project is located.
     */
    @Parameter( property = "siteDirectory", defaultValue = "src/site" )
    protected String siteDirectory;

    /**
     * Sets whether this plugin is running in offline or online mode. Also
     * useful when you don't want to verify http URLs.
     */
    @Parameter( property = "settings.offline" )
    private boolean offline;

    /**
     * The current user system settings for use in Maven.
     */
    @Parameter( defaultValue = "${settings}", readonly = true, required = true )
    private Settings settings;

    private CloseableHttpClient httpClient;

    private FileSetManager fileSetManager = new FileSetManager();

    private List<String> validUrls = new ArrayList<>();

    protected List<MavenProject> getReactorProjects()
    {
        return reactorProjects;
    }

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {

        String httpUserAgent = "maven-docck-plugin/1.x" + " (Java " + System.getProperty( "java.version" ) + "; "
                + System.getProperty( "os.name" ) + " " + System.getProperty( "os.version" ) + ")";
        HttpClientBuilder httpClientBuilder = HttpClients.custom()
              .setDefaultRequestConfig( RequestConfig.custom()
                      .setConnectTimeout( Timeout.ofSeconds( 5 ) )
                      .setResponseTimeout( Timeout.ofSeconds( 5 ) )
                      .setCookieSpec( StandardCookieSpec.STRICT )
                      .build() )
              .setDefaultHeaders( singletonList( new BasicHeader( HttpHeaders.USER_AGENT, httpUserAgent ) ) );

        setupProxy( httpClientBuilder );

        httpClient = httpClientBuilder.build();

        if ( output != null )
        {
            getLog().info( "Writing documentation check results to: " + output );
        }

        Map<MavenProject, DocumentationReporter> reporters = new LinkedHashMap<>();
        boolean hasErrors = false;

        for ( MavenProject project : reactorProjects )
        {
            if ( approveProjectPackaging( project.getPackaging() ) )
            {
                getLog().info( "Checking project: " + project.getName() );

                DocumentationReporter reporter = new DocumentationReporter();

                checkProject( project, reporter );

                if ( !hasErrors && reporter.hasErrors() )
                {
                    hasErrors = true;
                }

                reporters.put( project, reporter );
            }
            else
            {
                getLog().info( "Skipping unsupported project: " + project.getName() );
            }
        }

        String messages;

        messages = buildErrorMessages( reporters );

        if ( !hasErrors )
        {
            messages += "No documentation errors were found.";
        }

        try
        {
            writeMessages( messages, hasErrors );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error writing results to output file: " + output );
        }

        if ( hasErrors )
        {
            String logLocation;
            if ( output == null )
            {
                logLocation = "Please see the console output above for more information.";
            }
            else
            {
                logLocation = "Please see \'" + output + "\' for more information.";
            }

            throw new MojoFailureException( "Documentation problems were found. " + logLocation );
        }
    }

    /**
     * Setup proxy access if needed.
     * @param httpClientBuilder 
     */
    private void setupProxy( HttpClientBuilder httpClientBuilder )
    {
        Proxy settingsProxy = settings.getActiveProxy();

        if ( settingsProxy != null )
        {
            String proxyUsername = settingsProxy.getUsername();

            String proxyPassword = settingsProxy.getPassword();

            String proxyHost = settingsProxy.getHost();

            int proxyPort = settingsProxy.getPort();

            if ( StringUtils.isNotEmpty( proxyHost ) )
            {
                httpClientBuilder.setProxy( new HttpHost( proxyHost, proxyPort ) );

                getLog().info( "Using proxy [" + proxyHost + "] at port [" + proxyPort + "]." );

                if ( StringUtils.isNotEmpty( proxyUsername ) )
                {
                    getLog().info( "Using proxy user [" + proxyUsername + "]." );

                    BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(
                            new AuthScope( proxyHost, proxyPort ),
                            new UsernamePasswordCredentials( proxyUsername, proxyPassword.toCharArray() ) );

                    httpClientBuilder.setDefaultCredentialsProvider( credsProvider );
                }
            }
        }
    }

    private String buildErrorMessages( Map<MavenProject, DocumentationReporter> reporters )
    {
        String messages = "";
        StringBuilder buffer = new StringBuilder();

        for ( Map.Entry<MavenProject, DocumentationReporter> entry : reporters.entrySet() )
        {
            MavenProject project = entry.getKey();
            DocumentationReporter reporter = entry.getValue();

            if ( !reporter.getMessages().isEmpty() )
            {
                buffer.append( System.lineSeparator() ).append( "o " ).append( project.getName() );
                buffer.append( " (" );
                final int numberOfErrors = reporter.getMessagesByType( DocumentationReport.TYPE_ERROR ).size();
                buffer.append( numberOfErrors ).append( " error" ).append( numberOfErrors == 1 ? "" : "s" );
                buffer.append( ", " );
                final int numberOfWarnings = reporter.getMessagesByType( DocumentationReport.TYPE_WARN ).size();
                buffer.append( numberOfWarnings ).append( " warning" ).append( numberOfWarnings == 1 ? "" : "s" );
                buffer.append( ")" );
                buffer.append( System.lineSeparator() );

                for ( String error : reporter.getMessages() )
                {
                    buffer.append( "  " ).append( error ).append( System.lineSeparator() );
                }
            }
        }

        if ( buffer.length() > 0 )
        {
            messages = "The following documentation problems were found:" + System.lineSeparator() + buffer.toString();
        }

        return messages;
    }

    protected abstract boolean approveProjectPackaging( String packaging );

    /**
     * Writes the text in messages either to a file or to the console.
     *
     * @param messages The message text
     * @param hasErrors If there were any documentation errors
     * @throws IOException
     */
    private void writeMessages( String messages, boolean hasErrors )
        throws IOException
    {
        if ( output != null )
        {
            try ( FileWriter writer = new FileWriter( output ) )
            {
                writer.write( messages );
            }
        }
        else
        {
            if ( hasErrors )
            {
                getLog().error( messages );
            }
            else
            {
                getLog().info( messages );
            }
        }
    }

    private void checkProject( MavenProject project, DocumentationReporter reporter )
    {
        checkPomRequirements( project, reporter );

        checkPackagingSpecificDocumentation( project, reporter );
    }

    private void checkPomRequirements( MavenProject project, DocumentationReporter reporter )
    {
        checkProjectLicenses( project, reporter );

        if ( StringUtils.isEmpty( project.getName() ) )
        {
            reporter.error( "pom.xml is missing the <name> tag." );
        }

        if ( StringUtils.isEmpty( project.getDescription() ) )
        {
            reporter.error( "pom.xml is missing the <description> tag." );
        }

        if ( StringUtils.isEmpty( project.getUrl() ) )
        {
            reporter.error( "pom.xml is missing the <url> tag." );
        }
        else
        {
            checkURL( project.getUrl(), "project site", reporter );
        }

        if ( project.getIssueManagement() == null )
        {
            reporter.error( "pom.xml is missing the <issueManagement> tag." );
        }
        else
        {
            IssueManagement issueMngt = project.getIssueManagement();
            if ( StringUtils.isEmpty( issueMngt.getUrl() ) )
            {
                reporter.error( "pom.xml is missing the <url> tag in <issueManagement>." );
            }
            else
            {
                checkURL( issueMngt.getUrl(), "Issue Management", reporter );
            }
        }

        if ( project.getPrerequisites() == null )
        {
            reporter.error( "pom.xml is missing the <prerequisites> tag." );
        }
        else
        {
            Prerequisites prereq = project.getPrerequisites();
            if ( StringUtils.isEmpty( prereq.getMaven() ) )
            {
                reporter.error( "pom.xml is missing the <prerequisites>/<maven> tag." );
            }
        }

        if ( StringUtils.isEmpty( project.getInceptionYear() ) )
        {
            reporter.error( "pom.xml is missing the <inceptionYear> tag." );
        }

        if ( project.getMailingLists().size() == 0 )
        {
            reporter.warn( "pom.xml has no <mailingLists>/<mailingList> specified." );
        }

        if ( project.getScm() == null )
        {
            reporter.warn( "pom.xml is missing the <scm> tag." );
        }
        else
        {
            Scm scm = project.getScm();
            if ( StringUtils.isEmpty( scm.getConnection() ) && StringUtils.isEmpty( scm.getDeveloperConnection() )
                && StringUtils.isEmpty( scm.getUrl() ) )
            {
                reporter.warn( "pom.xml is missing the child tags under the <scm> tag." );
            }
            else if ( scm.getUrl() != null )
            {
                checkURL( scm.getUrl(), "scm", reporter );
            }
        }

        if ( project.getOrganization() == null )
        {
            reporter.error( "pom.xml is missing the <organization> tag." );
        }
        else
        {
            Organization org = project.getOrganization();
            if ( StringUtils.isEmpty( org.getName() ) )
            {
                reporter.error( "pom.xml is missing the <organization>/<name> tag." );
            }
            else if ( org.getUrl() != null )
            {
                checkURL( org.getUrl(), org.getName() + " site", reporter );
            }
        }
    }

    private void checkProjectLicenses( MavenProject project, DocumentationReporter reporter )
    {
        @SuppressWarnings( "unchecked" )
        List<License> licenses = project.getLicenses();

        if ( licenses == null || licenses.isEmpty() )
        {
            reporter.error( "pom.xml has no <licenses>/<license> specified." );
        }
        else
        {
            for ( License license : licenses )
            {
                if ( StringUtils.isEmpty( license.getName() ) )
                {
                    reporter.error( "pom.xml is missing the <licenses>/<license>/<name> tag." );
                }
                else
                {
                    String url = license.getUrl();
                    if ( StringUtils.isEmpty( url ) )
                    {
                        reporter.error( "pom.xml is missing the <licenses>/<license>/<url> tag for the license \'"
                            + license.getName() + "\'." );
                    }
                    else
                    {
                        checkURL( url, "license \'" + license.getName() + "\'", reporter );
                    }
                }
            }
        }
    }

    private String getURLProtocol( String url )
        throws MalformedURLException
    {
        URL licenseUrl = new URL( url );
        String protocol = licenseUrl.getProtocol();

        if ( protocol != null )
        {
            protocol = protocol.toLowerCase();
        }

        return protocol;
    }

    private void checkURL( String url, String description, DocumentationReporter reporter )
    {
        try
        {
            String protocol = getURLProtocol( url );

            if ( protocol.startsWith( "http" ) )
            {
                if ( offline )
                {
                    reporter.warn( "Cannot verify " + description + " in offline mode with URL: \'" + url + "\'." );
                }
                else if ( !validUrls.contains( url ) )
                {
                    HttpHead headMethod = new HttpHead( url );

                    try ( CloseableHttpResponse response = httpClient.execute( headMethod ) )
                    {
                        getLog().debug( "Verifying http url: " + url );
                        if ( response.getCode() != HTTP_STATUS_200 )
                        {
                            reporter.error( "Cannot reach " + description + " with URL: \'" + url + "\'." );
                        }
                        else
                        {
                            validUrls.add( url );
                        }
                    }
                    catch ( IOException e )
                    {
                        reporter.error( "Cannot reach " + description + " with URL: \'" + url + "\'.\nError: "
                            + e.getMessage() );
                    }
                }
            }
            else
            {
                reporter.warn( "Non-HTTP " + description + " URL not verified." );
            }
        }
        catch ( MalformedURLException e )
        {
            reporter.warn( "The " + description + " appears to have an invalid URL \'" + url + "\'."
                + " Message: \'" + e.getMessage() + "\'. Trying to access it as a file instead." );

            checkFile( url, description, reporter );
        }
    }

    private void checkFile( String url, String description, DocumentationReporter reporter )
    {
        File licenseFile = new File( url );
        if ( !licenseFile.exists() )
        {
            reporter.error( "The " + description + " in file \'" + licenseFile.getPath() + "\' does not exist." );
        }
    }

    protected abstract void checkPackagingSpecificDocumentation( MavenProject project, DocumentationReporter reporter );

    protected boolean findFiles( File siteDirectory, String pattern )
    {
        FileSet fs = new FileSet();
        fs.setDirectory( siteDirectory.getAbsolutePath() );
        fs.setFollowSymlinks( false );

        fs.addInclude( "apt/" + pattern + ".apt" );
        fs.addInclude( "apt/" + pattern + ".apt.vm" );
        fs.addInclude( "xdoc/" + pattern + ".xml" );
        fs.addInclude( "xdoc/" + pattern + ".xml.vm" );
        fs.addInclude( "fml/" + pattern + ".fml" );
        fs.addInclude( "fml/" + pattern + ".fml.vm" );
        fs.addInclude( "resources/" + pattern + ".html" );
        fs.addInclude( "resources/" + pattern + ".html.vm" );

        String[] includedFiles = fileSetManager.getIncludedFiles( fs );

        return includedFiles != null && includedFiles.length > 0;
    }
}
