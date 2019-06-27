package com.republicate.markdown.vtl;

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

import com.vladsch.flexmark.ext.abbreviation.AbbreviationExtension;
import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension;
import com.vladsch.flexmark.ext.aside.AsideExtension;
import com.vladsch.flexmark.ext.attributes.AttributesExtension;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.definition.DefinitionExtension;
import com.vladsch.flexmark.ext.emoji.EmojiExtension;
import com.vladsch.flexmark.ext.footnotes.FootnoteExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.SubscriptExtension;
import com.vladsch.flexmark.ext.media.tags.MediaTagsExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.toc.TocExtension;
import com.vladsch.flexmark.ext.typographic.TypographicExtension;
import com.vladsch.flexmark.ext.wikilink.WikiLinkExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.ParserEmulationProfile;
import com.vladsch.flexmark.superscript.SuperscriptExtension;
import com.vladsch.flexmark.util.options.MutableDataSet;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.velocity.Template;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.InternalContextAdapter;
import org.apache.velocity.exception.*;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.runtime.directive.Directive;
import org.apache.velocity.runtime.directive.MacroParseException;
import org.apache.velocity.runtime.parser.ParseException;
import org.apache.velocity.runtime.parser.Token;
import org.apache.velocity.runtime.parser.node.Node;
import org.apache.velocity.runtime.parser.node.ParserTreeConstants;
import org.apache.velocity.util.ExtProperties;
import org.apache.velocity.util.StringBuilderWriter;
import org.apache.velocity.util.StringUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>VTL <code>#markdown</code>(<i>resource name</i>) directive.</p>
 * <p>It uses a modified MarkdownVTLParser to parse Markdown templates.</p>
 * <p>The Velocity Engine instance which does the parsing and the merging
 * must be configured from the client <code>velocity.properties</code> file
 * with the <code>markdown</code> prefix, like:</p>
 * <pre><code>
 *    markdown.resource.loaders = file
 *    markdown.resource.loader.file... = ...
 * </code></pre>
 * <p>And the directive must be activated itself using:</p>
 * <pre><code>
 *     runtime.custom_directives = com.republicate.markdown.vtl.MarkdownDirective
 * </code></pre>
 */

public class MarkdownDirective extends Directive
{
    /**
     * Configuration key for markdown executor thread pool size
     */
    public static final String MARKDOWN_THREAD_POOL_SIZE_KEY = "com.republicate.markdown.thread_pool_size";

    /**
     * Default markdown thread pool size
     */
    private static final int DEFAULT_MARKDOWN_THREAD_POOL_SIZE = 10;

    /**
     * Path of default Velocity properties file for
     */
    private static final String DEFAULT_MDVTL_PROPERTIES = "com/republicate/markdown/velocity.properties";

    /**
     * Application attribute key for markdown VTL engine
     */
    private static final String MARKDOWN_VTL_ENGINE_KEY = "com.republicate.markdown.engine";

    /**
     * Application attribute key for markdown parser
     */
    private static final String MARKDOWN_PARSER_KEY = "com.republicate.markdown.parser";

    /**
     * Application attribute key for markdown HTML renderer
     */
    private static final String MARKDOWN_RENDERER_KEY = "com.republicate.markdown.renderer";

    /**
     * Application attribute key for markdown executor
     */
    private static final String MARKDOWN_EXECUTOR_KEY = "com.republicate.markdown.executor";

    /**
     * The Velocity engine used to parse markdown templates
     */
    private VelocityEngine engine = null;

    /**
     * The markdown parser
     */
    private Parser parser = null;

    /**
     * The markdown HTML renderer
     */
    private HtmlRenderer renderer = null;

    /**
     * the engine used for this instance
     */

    /**
     * Return the name of this directive.
     * @return The name of this directive.
     */
    @Override
    public String getName()
    {
        return "markdown";
    }

    /**
     * Get the directive type BLOCK/LINE.
     * @return The directive type BLOCK/LINE.
     */
    @Override
    public int getType()
    {
        return LINE;
    }

    /**
     * How this directive is to be initialized.
     * @param rs
     * @param context
     * @param node
     * @throws TemplateInitException
     */
    @Override
    public void init(RuntimeServices rs, InternalContextAdapter context, org.apache.velocity.runtime.parser.node.Node node)
        throws TemplateInitException
    {
        try
        {
            super.init(rs, context, node);
            engine = (VelocityEngine)rs.getApplicationAttribute(MARKDOWN_VTL_ENGINE_KEY);
            if (engine == null)
            {
                synchronized (rs)
                {
                    initMarkdownVTLEngine(rs);
                    initMarkdownProcessors(rs);
                    engine = (VelocityEngine)rs.getApplicationAttribute(MARKDOWN_VTL_ENGINE_KEY);
                }
            }
            parser = (Parser)rs.getApplicationAttribute(MARKDOWN_PARSER_KEY);
            renderer = (HtmlRenderer)rs.getApplicationAttribute(MARKDOWN_RENDERER_KEY);
        }
        catch (IOException e)
        {
            throw new VelocityException("could not init #markdown directive", e);
        }
    }

    private void initMarkdownVTLEngine(RuntimeServices rs) throws IOException
    {
        if (rs.getApplicationAttribute(MARKDOWN_VTL_ENGINE_KEY) == null)
        {
            // create Velocity engine
            VelocityEngine engine = new VelocityEngine();

            // load default properties
            InputStream inputStream = MarkdownDirective.class.getClassLoader().getResourceAsStream(DEFAULT_MDVTL_PROPERTIES);
            if (inputStream == null)
            {
                throw new IOException("could not find classpath resource: " + DEFAULT_MDVTL_PROPERTIES);
            }
            Properties defaultProps = new Properties();
            defaultProps.load(inputStream);
            engine.setProperties(defaultProps);

            // load user properties
            ExtProperties extProps = rs.getConfiguration().subset("markdown");
            if (extProps != null)
            {
                Properties userProps = new Properties();
                userProps.putAll(extProps);
                /* we must circumvent the Velocity 2.1+ properties deprecation mechanism,
                * which tanslates markdown.resource.loader.xxx.yyy into resource.loader.markdown.xxx.yyy */
                extProps = rs.getConfiguration().subset("resource.loader.markdown");
                for (Map.Entry<String, Object> entry : extProps.entrySet())
                {
                    userProps.put("resource.loader." + entry.getKey(), entry.getValue());
                }
                engine.setProperties(userProps);
            }

            // init engine
            engine.init();

            // store engine
            rs.setApplicationAttribute(MARKDOWN_VTL_ENGINE_KEY, engine);
        }
    }

    private void initMarkdownProcessors(RuntimeServices rs)
    {
        if (rs.getApplicationAttribute(MARKDOWN_PARSER_KEY) == null)
        {
            // init flexmark
            MutableDataSet options = new MutableDataSet();
            options.set(Parser.PARSER_EMULATION_PROFILE, ParserEmulationProfile.MULTI_MARKDOWN);
            options.set(Parser.EXTENSIONS, Arrays.asList(
                AbbreviationExtension.create(),
                AnchorLinkExtension.create(),
                AsideExtension.create(),
                AttributesExtension.create(),
                AutolinkExtension.create(),
                DefinitionExtension.create(),
                EmojiExtension.create(),
                FootnoteExtension.create(),
                MediaTagsExtension.create(),
                // generates error: java.lang.IllegalArgumentException: Delimiter processor conflict with delimiter char '~'
                // StrikethroughExtension.create(),
                SubscriptExtension.create(),
                SuperscriptExtension.create(),
                TablesExtension.create(),
                TocExtension.create(),
                TypographicExtension.create(),
                WikiLinkExtension.create()
            ));

            // uncomment to convert soft-breaks to hard breaks
            //options.set(HtmlRenderer.SOFT_BREAK, "<br />\n");

            Parser parser = Parser.builder(options).build();
            HtmlRenderer renderer = HtmlRenderer.builder(options).build();

            // store markdown processors
            rs.setApplicationAttribute(MARKDOWN_PARSER_KEY, parser);
            rs.setApplicationAttribute(MARKDOWN_RENDERER_KEY, renderer);

            // create markdown executor threads pool
            int maxThreads = rs.getInt(MARKDOWN_THREAD_POOL_SIZE_KEY, DEFAULT_MARKDOWN_THREAD_POOL_SIZE);
            ExecutorService executor = Executors.newFixedThreadPool(maxThreads);

            // store markdown executor
            rs.setApplicationAttribute(MARKDOWN_EXECUTOR_KEY, executor);
        }
    }

    /**
     * The Parser calls this method during template parsing to check the arguments
     * types.  Be aware that this method is called pre init, so not all data
     * is available in this method.  The default implementation does not peform any
     * checking.  We do this so that Custom directives do not trigger any parse
     * errors in IDEs.
     * @param argtypes type, Array of argument types of each argument to the directive
     * for example ParserTreeConstants.JJTWORD
     * @param t token of directive
     * @param templateName the name of the template this directive is referenced in.
     * @throws ParseException
     */
    @Override
    public void checkArgs(ArrayList<Integer> argtypes, Token t, String templateName)
        throws ParseException
    {
        super.checkArgs(argtypes, t, templateName);
        if (argtypes.size() != 1)
        {
            throw new MacroParseException("The #markdown directive requires one argument", templateName, t);
        }

        if (argtypes.get(0) == ParserTreeConstants.JJTWORD)
        {
            throw new MacroParseException("The argument to #markdown is of the wrong type", templateName, t);
        }
    }
    
    /**
     * How this directive is to be rendered
     * @param context
     * @param writer
     * @param node
     * @return True if the directive rendered successfully.
     * @throws IOException
     * @throws ResourceNotFoundException
     * @throws ParseErrorException
     * @throws MethodInvocationException
     */
    @Override
    public boolean render(InternalContextAdapter context, final Writer writer, org.apache.velocity.runtime.parser.node.Node node )
           throws IOException, ResourceNotFoundException, ParseErrorException, MethodInvocationException
    {
        // did we get an argument?
        if ( node.jjtGetNumChildren() == 0 )
        {
            throw new VelocityException("#markdown(): argument missing at " + StringUtils.formatFileString(this));
        }

        // does it have a value?  If you have a null reference, then no.
        Object value =  node.jjtGetChild(0).value( context );
        if (value == null)
        {
            log.debug("#markdown(): null argument at {}", StringUtils.formatFileString(this));
        }

        // get resource path
        String resourcePath = value == null ? null : value.toString();

        // we may gain perfs by using piped readers & writers and a thread pool executor,
        // but this is not a guarantee (probably not interesting for small chunks)
        // and, last but foremost, we don't now *when* to shutdown this background executor

        StringBuilderWriter markdownOutput = new StringBuilderWriter();
        engine.mergeTemplate(resourcePath, "UTF-8", context, markdownOutput); // TODO - parametrize encoding
        com.vladsch.flexmark.util.ast.Node document = parser.parse(markdownOutput.toString());
        renderer.render(document, writer);

        return true;
    }
}
