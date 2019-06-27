# velocity-markdown-directive

## Purpose

This library provides a `#markdown`(*filename*) directive, which:

+ loads a markdown template
+ evaluates it against the current context using a custom Velocity parser which uses '@' in place of '#' (and '%' instead of '@')
+ renders the HTML result using flexmark in the current template

## Configuration

In `velocity.properties`, declare the markdown directive as a custom directive, and set up the resource loader(s) and any other setting
which will be used when merging markdown templates under the `markdown.` prefix:

    runtime.custom_directives = com.republicate.markdown.vtl.MarkdownDirective
    markdown.resource.loaders = file
    markdown.resource.loader.file.path = .

