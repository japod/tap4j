/*
 * The MIT License
 *
 * Copyright (c) 2010 tap4j team (see AUTHORS)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.tap4j.parser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.UnsupportedCharsetException;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.tap4j.model.BailOut;
import org.tap4j.model.Comment;
import org.tap4j.model.Footer;
import org.tap4j.model.Header;
import org.tap4j.model.Plan;
import org.tap4j.model.TapElement;
import org.tap4j.model.TapElementFactory;
import org.tap4j.model.TestResult;
import org.tap4j.model.TestSet;
import org.tap4j.model.Text;

import org.yaml.snakeyaml.Yaml;

/**
 * TAP 13 parser.
 *
 * @since 1.0
 */
public class Tap13Parser implements Parser {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(Tap13Parser.class
            .getCanonicalName());

    /**
     * Stack of stream status information bags. Every bag stores state of the parser
     * related to certain indentation level. This is to support subtest feature.
     */
    private Stack<StreamStatus> states = new Stack<StreamStatus>();

    /**
     * The current state.
     */
    private StreamStatus state = null;

    private int baseIndentation;
    /**
     * Decoder used. This is only used when trying to parse something that is
     * encoded (like a raw file or byte stream) and the encoding isn't otherwise
     * known.
     */
    private CharsetDecoder decoder;

    /**
     * Require a TAP plan.
     */
    private boolean planRequired = true;

    /**
     * Remove corrupted YAML. YAML parser error should not cause TAP parser error.
     * The content that failed to be parsed as YAML will be just removed from the TAP diagnostic data.
     * Switched off by default.
     */
    private boolean removeYamlIfCorrupted = false;

    /**
     * Parser Constructor.
     *
     * A parser constructed this way will enforce that any input should include
     * a plan.
     *
     * @param encoding Encoding. This will not matter when parsing sources that
     * are already decoded (e.g. {@link String} or {@link Readable}), but it
     * will be used in the {@link #parseFile} method (whether or not it is the
     * right encoding for the File being parsed).
     * @param enableSubtests Whether subtests are enabled or not
     */
    public Tap13Parser(String encoding, boolean enableSubtests) {
        this(encoding, enableSubtests, true);
    }

    /**
     * Parser Constructor.
     *
     * @param encoding Encoding. This will not matter when parsing sources that
     * are already decoded (e.g. {@link String} or {@link Readable}), but it
     * will be used in the {@link #parseFile} method (whether or not it is the
     * right encoding for the File being parsed).
     * @param enableSubtests Whether subtests are enabled or not
     * @param planRequired flag that defines whether a plan is required or not
     */
    public Tap13Parser(String encoding, boolean enableSubtests, boolean planRequired) {
        this(encoding, enableSubtests, planRequired, false);
    }

    /**
     * Parser Constructor.
     *
     * @param encoding Encoding. This will not matter when parsing sources that
     * are already decoded (e.g. {@link String} or {@link Readable}), but it
     * will be used in the {@link #parseFile} method (whether or not it is the
     * right encoding for the File being parsed).
     * @param enableSubtests Whether subtests are enabled or not
     * @param planRequired flag that defines whether a plan is required or not
     * @param removeYamlIfCorrupted flag that defines whether a corrupted YAML content will be removed without causing whole TAP processing failure
     */
    public Tap13Parser(String encoding, boolean enableSubtests, boolean planRequired, boolean removeYamlIfCorrupted) {
        super();
        /*
         * Resolving the encoding name to a CharsetDecoder here has two
         * benefits. First, if it isn't known or supported, the caller finds out
         * as early as possible. Second, a decoder obtained this way will check
         * the validity of its input and throw exceptions if it doesn't match
         * the encoding. All the other ways to specify an encoding result in a
         * default behavior to silently drop or change data ... not great in a
         * testing tool.
         */
        try {
            if (null != encoding) {
                this.decoder = Charset.forName(encoding).newDecoder();
            }
        } catch (UnsupportedCharsetException uce) {
            throw new ParserException("Invalid encoding: " + encoding, uce);
        }
        this.planRequired = planRequired;
        this.removeYamlIfCorrupted = removeYamlIfCorrupted;
    }

    /**
     * Parser Constructor.
     *
     * A parser created with this constructor will assume that any input to the
     * {@link #parseFile} method is encoded in {@code UTF-8}.
     *
     * @param enableSubtests Whether subtests are enabled or not
     */
    public Tap13Parser(boolean enableSubtests) {
        this("UTF-8", enableSubtests);
    }

    /**
     * Parser Constructor.
     *
     * A parser created with this constructor will assume that any input to the
     * {@link #parseFile} method is encoded in {@code UTF-8}, and will not
     * recognize subtests.
     */
    public Tap13Parser() {
        this("UTF-8", false);
    }

    /**
     * Saves the current state in the stack.
     * @param indentation state indentation
     */
    private void pushState(int indentation) {
        states.push(state);
        state = new StreamStatus();
        state.setIndentationLevel(indentation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TestSet parseTapStream(String tapStream) {
        return parseTapStream(CharBuffer.wrap(tapStream));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TestSet parseFile(File tapFile) {
        if (null == decoder) {
            throw new ParserException(
                "Must have encoding specified if using parseFile");
        }
        FileInputStream fis = null;
        InputStreamReader isr = null;
        try {
            fis = new FileInputStream(tapFile);
            isr = new InputStreamReader(fis, decoder);
            return parseTapStream(isr);
        } catch (FileNotFoundException e) {
            throw new ParserException("TAP file not found: " + tapFile, e);
        } finally {
            try  {
                if (isr != null) {
                    isr.close();
                }
                if (fis != null) {
                    fis.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to close file stream: "
                        + e.getMessage(), e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TestSet parseTapStream(Readable tapStream) {
        state = new StreamStatus();
        baseIndentation = Integer.MAX_VALUE;
        Scanner scanner = null;
        try {
            scanner = new Scanner(tapStream);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line != null && line.length() > 0) {
                    parseLine(line);
                }
            }
            onFinish();
        } catch (Exception e) {
            throw new ParserException("Error parsing TAP Stream: "
                    + e.getMessage(), e);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }

        return state.getTestSet();
    }

    /**
     * Parse a TAP line.
     *
     * @param tapLineOrig TAP line
     */
    public void parseLine(String tapLineOrig) {

        // filter out cursor related control sequences ESC[25?l and ESC[25?h
        String tapLine = tapLineOrig.replaceAll("\u001B\\[\\?25[lh]", "");

        TapElement tapElement = TapElementFactory.createTapElement(tapLine);

        if (tapElement == null || state.isInYaml()) {

            String trimmedLine = tapLine.trim();
            Text text = TapElementFactory.createTextElement(tapLine);

            if (state.isInYaml()) {

                boolean yamlEndMarkReached = trimmedLine.equals("...") && (
                           tapLine.equals(state.getYamlIndentation() + "...")
                        || text.getIndentation() < state.getYamlIndentation().length());

                if (yamlEndMarkReached) {
                    state.setInYaml(false);
                    parseDiagnostics();
                } else {
                    state.getDiagnosticBuffer().append(tapLine);
                    state.getDiagnosticBuffer().append('\n');
                }
            } else {
                if (trimmedLine.equals("---")) {
                    if (text.getIndentation() < baseIndentation) {
                        throw new ParserException("Invalid indentation. "
                                    + "Check your TAP Stream. Line: " + tapLine);
                    }
                    state.setInYaml(true);
                    state.setYamlIndentation(text.getIndentationString());
                } else {
                    state.getTestSet().getTapLines().add(text);
                    state.setLastParsedElement(text);
                }
            }
            return;
        }

        int indentation = tapElement.getIndentation();

        if (indentation < baseIndentation) {
            baseIndentation = indentation;
        }

        if (indentation != state.getIndentationLevel()) { // indentation changed

            if (indentation > state.getIndentationLevel()) {
                StreamStatus parentState = state;
                pushState(indentation); // make room for children

                TapElement lastParentElement = parentState.getLastParsedElement();
                if (lastParentElement instanceof TestResult) {
                    final TestResult lastTestResult = (TestResult) lastParentElement;
                    // whatever test set comes should be attached to parent
                    if (lastTestResult.getSubtest() == null) {
                        lastTestResult.setSubtest(state.getTestSet());
                        state.attachedToParent = true;
                    }
                }
            } else {
                // going down
                do {
                    StreamStatus prevState = state;
                    state = states.pop();
                    if (!prevState.attachedToParent) {
                        state.looseSubtests = prevState.getTestSet();
                    }
                    // there could be more than one level diff
                } while (indentation < state.getIndentationLevel());
            }
        }

        if (tapElement instanceof Header) {

            if (state.getTestSet().getHeader() != null) {
                throw new ParserException("Duplicated TAP Header found.");
            }
            if (!state.isFirstLine()) {
                throw new ParserException(
                        "Invalid position of TAP Header. It must be the first "
                                + "element (apart of Comments) in the TAP Stream.");
            }
            state.getTestSet().setHeader((Header) tapElement);

        } else if (tapElement instanceof Plan) {

            Plan currentPlan = (Plan) tapElement;

            if (state.getTestSet().getPlan() != null) {
                if (currentPlan.getInitialTestNumber() != 1 || currentPlan.getLastTestNumber() != 0) {
                    throw new ParserException("Duplicated TAP Plan found.");
                }
            } else {
                state.getTestSet().setPlan(currentPlan);
            }

            if (state.getTestSet().getTestResults().size() <= 0
                    && state.getTestSet().getBailOuts().size() <= 0) {
                state.setPlanBeforeTestResult(true);
            }

        } else if (tapElement instanceof TestResult) {

            parseDiagnostics();

            final TestResult testResult = (TestResult) tapElement;
            if (testResult.getTestNumber() == 0) {
                if (state.getTestSet().getPlan() != null && state.isPlanBeforeTestResult() == false) {
                    return; // done testing mark
                }
                if (state.getTestSet().getPlan() != null &&
                    state.getTestSet().getPlan().getLastTestNumber() == state.getTestSet().getTestResults().size()) {
                    return; // done testing mark but plan before test result
                }
                testResult.setTestNumber(state.getTestSet().getNextTestNumber());
            }

            state.getTestSet().addTestResult(testResult);
            if (state.looseSubtests != null) {
                testResult.setSubtest(state.looseSubtests);
                state.looseSubtests = null;
            }

        } else if (tapElement instanceof Footer) {

            state.getTestSet().setFooter((Footer) tapElement);

        } else if (tapElement instanceof BailOut) {

            state.getTestSet().addBailOut((BailOut) tapElement);

        } else if (tapElement instanceof Comment) {

            final Comment comment = (Comment) tapElement;

            state.getTestSet().addComment(comment);

            if (state.getLastParsedElement() instanceof TestResult) {
                ((TestResult) state.getLastParsedElement()).addComment(comment);
            }
        }

        state.setFirstLine(false);
        if (!(tapElement instanceof Comment)) {
            state.setLastParsedElement(tapElement);
        }
    }

    /**
     * Called after the rest of the stream has been processed.
     */
    private void onFinish() {
        if (planRequired) {
            if (state.getTestSet().getPlan() == null) {
                throw new ParserException("Missing TAP Plan.");
            }
        }
        parseDiagnostics();

        while (!states.isEmpty() && state.getIndentationLevel() > baseIndentation) {
            state = states.pop();
        }
    }

    /* -- Utility methods --*/

    /**
     * <p>
     * Checks if there is any diagnostic information on the diagnostic buffer.
     * </p>
     * <p>
     * If so, tries to parse it using snakeyaml.
     * </p>
     */
    private void parseDiagnostics() {
        // If we found any meta, then process it with SnakeYAML
        if (state.getDiagnosticBuffer().length() > 0) {

            if (state.getLastParsedElement() == null) {
                throw new ParserException("Found diagnostic information without a previous TAP element.");
            }

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> metaIterable = (Map<String, Object>) new Yaml()
                        .load(state.getDiagnosticBuffer().toString());
                state.getLastParsedElement().setDiagnostic(metaIterable);
            } catch (Exception ex) {
                if (this.removeYamlIfCorrupted) {
                    Map<String, Object> metaInfo = new HashMap<String, Object>();
                    metaInfo.put("TAP processing error", "could not parse original diagnostic YAML data");
                    state.getLastParsedElement().setDiagnostic(metaInfo);
                } else {
                    throw new ParserException("Error parsing YAML ["
                            + state.getDiagnosticBuffer().toString() + "]: "
                            + ex.getMessage(), ex);
                }
            }
            this.state.getDiagnosticBuffer().setLength(0);
        }
    }
}
