/*
 * The MIT License
 *
 * Copyright 2020 Intuit Inc.
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
package com.intuit.karate.runtime;

import com.intuit.karate.AssignType;
import com.intuit.karate.Logger;
import com.intuit.karate.StringUtils;
import com.intuit.karate.XmlUtils;
import com.intuit.karate.data.Json;
import com.intuit.karate.data.JsonUtils;
import com.intuit.karate.graal.JsEngine;
import com.intuit.karate.match.Match;
import com.intuit.karate.match.MatchResult;
import com.intuit.karate.match.MatchType;
import com.intuit.karate.match.MatchValue;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 *
 * @author pthomas3
 */
public class ScenarioEngine {
    
    private final Logger logger;
    public final Map<String, Variable> vars = new HashMap();
    private JsEngine JS;
    
    public ScenarioEngine() {
        this(new Logger());
    }
    
    public ScenarioEngine(Logger logger) {
        this.logger = logger;
    }
    
    public void init() {
        JsEngine.remove(); // reset JS engine for this thread
        JS = JsEngine.global();
    }
    
    public Variable eval(String exp) {
        vars.forEach((k, v) -> JS.put(k, v.getValueForJsEngine()));
        return new Variable(JS.eval(exp));
    }
    
    public void put(String key, Variable value) {
        vars.put(key, value);
    }
    
    public void put(String key, Object value) {
        vars.put(key, new Variable(value));
    }
    
    private static void validateVariableName(String name) {
        if (!isValidVariableName(name)) {
            throw new RuntimeException("invalid variable name: " + name);
        }
        if (VariableNames.KARATE.equals(name)) {
            throw new RuntimeException("'karate' is a reserved name");
        }
        if (VariableNames.REQUEST.equals(name) || "url".equals(name)) {
            throw new RuntimeException("'" + name + "' is a reserved name, also use the form '* " + name + " <expression>' instead");
        }
    }
    
    private Variable evalAndCastTo(AssignType assignType, String exp) {
        Variable v = evalKarateExpression(exp);
        switch (assignType) {
            case BYTE_ARRAY:
                return new Variable(v.getAsByteArray());
            case STRING:
                return new Variable(v.getAsString());
            case XML:
                return new Variable(v.getAsXml());
            case XML_STRING:
                String xml = XmlUtils.toString(v.getAsXml());
                return new Variable(xml);
            case JSON:
                return new Variable(v.getValueForJsonConversion());
            case YAML:
                return new Variable(JsonUtils.fromYaml(v.getAsString()));
            case CSV:
                return new Variable(JsonUtils.fromCsv(v.getAsString()));
            case COPY:
                return v.copy(true);
            default: // AUTO (TEXT is pre-handled)
                return v; // as is
        }
    }
    
    public void assign(AssignType assignType, String name, String exp, boolean validateName) {
        name = StringUtils.trimToEmpty(name);
        if (validateName) {
            validateVariableName(name);
            if (vars.containsKey(name)) {
                logger.warn("over-writing existing variable {} with new value: {}", name, exp);
            }
        }
        if (assignType == AssignType.TEXT) {
            put(name, exp);
        } else {
            put(name, evalAndCastTo(assignType, exp));
        }
    }
    
    public boolean assertTrue(String expression) {
        return eval(expression).isTrue();
    }
    
    public Variable getIfVariableReference(String name) {
        // don't re-evaluate if this is clearly a direct reference to a variable
        // this avoids un-necessary conversion of xml into a map in some cases
        // e.g. 'Given request foo' - where foo is a ScriptValue of type XML
        // also e.g. 'print foo'        
        return vars.containsKey(name) ? vars.get(name) : null;
    }
    
    private static boolean isEmbeddedExpression(String text) {
        return (text.startsWith("#(") || text.startsWith("##(")) && text.endsWith(")");
    }
    
    private static class EmbedAction {
        
        final boolean remove;
        final Object value;
        
        private EmbedAction(boolean remove, Object value) {
            this.remove = remove;
            this.value = value;
        }
        
        static EmbedAction remove() {
            return new EmbedAction(true, null);
        }
        
        static EmbedAction update(Object value) {
            return new EmbedAction(false, value);
        }
        
    }
    
    public Variable evalEmbeddedExpressions(Variable value) {
        switch (value.type) {
            case STRING:
            case MAP:
            case LIST:
                EmbedAction ea = recurseEmbeddedExpressions(value);
                if (ea != null) {
                    return ea.remove ? Variable.NULL : new Variable(ea.value);
                } else {
                    return value;
                }
            case XML:
                recurseXmlEmbeddedExpressions(value.getValue());
            default:
                return value;
        }
    }
    
    private EmbedAction recurseEmbeddedExpressions(Variable node) {
        switch (node.type) {
            case LIST:
                List list = node.getValue();
                Set<Integer> indexesToRemove = new HashSet();
                int count = list.size();
                for (int i = 0; i < count; i++) {
                    EmbedAction ea = recurseEmbeddedExpressions(new Variable(list.get(i)));
                    if (ea != null) {
                        if (ea.remove) {
                            indexesToRemove.add(i);
                        } else {
                            list.set(i, ea.value);
                        }
                    }
                }
                if (!indexesToRemove.isEmpty()) {
                    List copy = new ArrayList(count - indexesToRemove.size());
                    for (int i = 0; i < count; i++) {
                        if (!indexesToRemove.contains(i)) {
                            copy.add(list.get(i));
                        }
                    }
                    return EmbedAction.update(copy);
                } else {
                    return null;
                }
            case MAP:
                Map<String, Object> map = node.getValue();
                List<String> keysToRemove = new ArrayList();
                map.forEach((k, v) -> {
                    EmbedAction ea = recurseEmbeddedExpressions(new Variable(v));
                    if (ea != null) {
                        if (ea.remove) {
                            keysToRemove.add(k);
                        } else {
                            map.put(k, ea.value);
                        }
                    }
                });
                for (String key : keysToRemove) {
                    map.remove(key);
                }
                return null;
            case XML:
                return null;
            case STRING:
                String value = StringUtils.trimToNull(node.getValue());
                if (!isEmbeddedExpression(value)) {
                    return null;
                }
                boolean remove = value.charAt(1) == '#';
                value = value.substring(remove ? 2 : 1);
                Variable result = eval(value);
                return remove && result.isNull() ? EmbedAction.remove() : EmbedAction.update(result.getValue());
            default:
                // do nothing
                return null;
        }
    }
    
    private void recurseXmlEmbeddedExpressions(Node node) {
        if (node.getNodeType() == Node.DOCUMENT_NODE) {
            node = node.getFirstChild();
        }
        NamedNodeMap attribs = node.getAttributes();
        int attribCount = attribs == null ? 0 : attribs.getLength();
        Set<Attr> attributesToRemove = new HashSet(attribCount);
        for (int i = 0; i < attribCount; i++) {
            Attr attrib = (Attr) attribs.item(i);
            String value = attrib.getValue();
            value = StringUtils.trimToNull(value);
            if (isEmbeddedExpression(value)) {
                boolean optional = value.charAt(1) == '#';
                value = value.substring(optional ? 2 : 1);
                try {
                    Variable v = eval(value);
                    if (optional && v.isNull()) {
                        attributesToRemove.add(attrib);
                    } else {
                        attrib.setValue(v.getAsString());
                    }
                } catch (Exception e) {
                    logger.trace("embedded xml-attribute eval failed, path: {}, reason: {}", attrib.getName(), e.getMessage());
                }
            }
        }
        for (Attr toRemove : attributesToRemove) {
            attribs.removeNamedItem(toRemove.getName());
        }
        NodeList nodeList = node.getChildNodes();
        int childCount = nodeList.getLength();
        List<Node> nodes = new ArrayList(childCount);
        for (int i = 0; i < childCount; i++) {
            nodes.add(nodeList.item(i));
        }
        Set<Node> elementsToRemove = new HashSet(childCount);
        for (Node child : nodes) {
            String value = child.getNodeValue();
            if (value != null) {
                value = StringUtils.trimToEmpty(value);
                if (isEmbeddedExpression(value)) {
                    boolean optional = value.charAt(1) == '#';
                    value = value.substring(optional ? 2 : 1);
                    try {
                        Variable v = eval(value);
                        if (optional && v.isNull()) {
                            elementsToRemove.add(child);
                        } else {
                            if (v.isXml() || v.isMap()) {
                                Node evalNode = v.isXml() ? v.getValue() : XmlUtils.fromMap(v.getValue());
                                if (evalNode.getNodeType() == Node.DOCUMENT_NODE) {
                                    evalNode = evalNode.getFirstChild();
                                }
                                if (child.getNodeType() == Node.CDATA_SECTION_NODE) {
                                    child.setNodeValue(XmlUtils.toString(evalNode));
                                } else {
                                    evalNode = node.getOwnerDocument().importNode(evalNode, true);
                                    child.getParentNode().replaceChild(evalNode, child);
                                }
                            } else {
                                child.setNodeValue(v.getAsString());
                            }
                        }
                    } catch (Exception e) {
                        logger.trace("embedded xml-text eval failed, path: {}, reason: {}", child.getNodeName(), e.getMessage());
                    }
                }
            } else if (child.hasChildNodes() || child.hasAttributes()) {
                recurseXmlEmbeddedExpressions(child);
            }
        }
        for (Node toRemove : elementsToRemove) { // because of how the above routine works, these are always of type TEXT_NODE
            Node parent = toRemove.getParentNode(); // element containing the text-node
            Node grandParent = parent.getParentNode(); // parent element
            grandParent.removeChild(parent);
        }
    }
    
    public static StringUtils.Pair parseVariableAndPath(String text) {
        Matcher matcher = VAR_AND_PATH_PATTERN.matcher(text);
        matcher.find();
        String name = text.substring(0, matcher.end());
        String path;
        if (matcher.end() == text.length()) {
            path = "";
        } else {
            path = text.substring(matcher.end()).trim();
        }
        if (isXmlPath(path) || isXmlPathFunction(path)) {
            // xml, don't prefix for json
        } else {
            path = "$" + path;
        }
        return StringUtils.pair(name, path);
    }
    
    public MatchResult match(MatchType matchType, String expression, String path, String expected) {
        String name = StringUtils.trimToEmpty(expression);
        if (isDollarPrefixedJsonPath(name) || isXmlPath(name)) { // 
            path = name;
            name = VariableNames.RESPONSE;
        }
        if (name.startsWith("$")) { // in case someone used the dollar prefix by mistake on the LHS
            name = name.substring(1);
        }
        path = StringUtils.trimToNull(path);
        if (path == null) {
            StringUtils.Pair pair = parseVariableAndPath(name);
            name = pair.left;
            path = pair.right;
        }
        if ("header".equals(name)) { // convenience shortcut for asserting against response header
            return match(matchType, VariableNames.RESPONSE_HEADERS, "$['" + path + "'][0]", expected);
        }
        Variable actual;
        // karate started out by "defaulting" to JsonPath on the LHS of a match so we have this kludge
        // but we now handle JS expressions of almost any shape on the LHS, if in doubt, wrap in parentheses
        // actually it is not too bad - the XPath function check is the only odd one out
        // rules:
        // if not XPath function, wrapped in parentheses, involves function call
        //      [then] JS eval
        // else if XPath, JsonPath, JsonPath wildcard ".." or "*" or "[?"
        //      [then] eval name, and do a JsonPath or XPath using the parsed path
        if (isXmlPathFunction(path)
                || (!name.startsWith("(") && !path.endsWith(")") && !path.contains(")."))
                && (isDollarPrefixed(path) || isJsonPath(path) || isXmlPath(path))) {
            actual = evalKarateExpression(name);
            // edge case: java property getter, e.g. "driver.cookies"
            if (!actual.isMap() && !actual.isList() && !isXmlPath(path) && !isXmlPathFunction(path)) {
                actual = evalKarateExpression(expression); // fall back to JS eval of entire LHS
                path = "$";
            }
        } else {
            actual = evalKarateExpression(expression); // JS eval of entire LHS
            path = "$";
        }
        Variable expectedVariable = evalKarateExpression(expected);
        MatchValue expectedValue = new MatchValue(expectedVariable.getValue());
        if ("$".equals(path) || "/".equals(path)) {
            // we have eval-ed the entire LHS, so proceed to match RHS to "$"
        } else {
            if (isDollarPrefixed(path)) { // json-path
                actual = evalJsonPath(actual, path);
            } else { // xpath
                actual = evalXmlPath(actual, path);
            }
        }
        MatchValue actualValue = new MatchValue(actual.getValue());
        return Match.execute(matchType, actualValue, expectedValue);
    }
    
    private static final Pattern VAR_AND_PATH_PATTERN = Pattern.compile("\\w+");
    private static final String VARIABLE_PATTERN_STRING = "[a-zA-Z][\\w]*";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile(VARIABLE_PATTERN_STRING);
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("^function[^(]*\\(");
    
    public static boolean isJavaScriptFunction(String text) {
        return FUNCTION_PATTERN.matcher(text).find();
    }
    
    public static String fixJavaScriptFunction(String text) {
        Matcher matcher = FUNCTION_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.replaceFirst("function(");
        } else {
            return text;
        }
    }
    
    public static boolean isValidVariableName(String name) {
        return VARIABLE_PATTERN.matcher(name).matches();
    }
    
    public static final boolean isVariableAndSpaceAndPath(String text) {
        return text.matches("^" + VARIABLE_PATTERN_STRING + "\\s+.+");
    }
    
    public static final boolean isVariable(String text) {
        return VARIABLE_PATTERN.matcher(text).matches();
    }
    
    public static final boolean isCallSyntax(String text) {
        return text.startsWith("call ");
    }
    
    public static final boolean isCallOnceSyntax(String text) {
        return text.startsWith("callonce ");
    }
    
    public static final boolean isGetSyntax(String text) {
        return text.startsWith("get ") || text.startsWith("get[");
    }
    
    public static final boolean isJson(String text) {
        return text.startsWith("{") || text.startsWith("[");
    }
    
    public static final boolean isXml(String text) {
        return text.startsWith("<");
    }
    
    public static boolean isXmlPath(String text) {
        return text.startsWith("/");
    }
    
    public static boolean isXmlPathFunction(String text) {
        return text.matches("^[a-z-]+\\(.+");
    }
    
    public static final boolean isJsonPath(String text) {
        return text.indexOf('*') != -1 || text.contains("..") || text.contains("[?");
    }
    
    public static final boolean isDollarPrefixed(String text) {
        return text.startsWith("$");
    }
    
    public static final boolean isDollarPrefixedJsonPath(String text) {
        return text.startsWith("$.") || text.startsWith("$[") || text.equals("$");
    }
    
    public static StringUtils.Pair parseCallArgs(String line) {
        int pos = line.indexOf("read(");
        if (pos != -1) {
            pos = line.indexOf(')');
            if (pos == -1) {
                throw new RuntimeException("failed to parse call arguments: " + line);
            }
            return new StringUtils.Pair(line.substring(0, pos + 1), line.substring(pos + 1));
        }
        pos = line.indexOf(' ');
        if (pos == -1) {
            return new StringUtils.Pair(line, null);
        }
        return new StringUtils.Pair(line.substring(0, pos), line.substring(pos));
    }
    
    public static Variable call(Variable called, String argString, boolean reuseParentConfig) {
        return null;
    }
    
    private static Variable callWithCache(String callKey, Variable called, String arg, boolean reuseParentConfig) {
        return null;
    }
    
    public Variable evalJsonPath(Variable v, String path) {
        Json json = new Json(v.getValueForJsonConversion());
        try {
            return new Variable(json.get(path));
        } catch (PathNotFoundException e) {
            return Variable.NOT_PRESENT;
        }
    }
    
    public Variable evalXmlPath(Variable xml, String path) {
        NodeList nodeList;
        Node doc = xml.getAsXml();
        try {
            nodeList = XmlUtils.getNodeListByPath(doc, path);
        } catch (Exception e) {
            // hack, this happens for xpath functions that don't return nodes (e.g. count)
            String strValue = XmlUtils.getTextValueByPath(doc, path);
            Variable v = new Variable(strValue);
            if (path.startsWith("count")) { // special case
                return new Variable(v.getAsInt());
            } else {
                return v;
            }
        }
        int count = nodeList.getLength();
        if (count == 0) { // xpath / node does not exist !
            return Variable.NOT_PRESENT;
        }
        if (count == 1) {
            return nodeToValue(nodeList.item(0));
        }
        List list = new ArrayList();
        for (int i = 0; i < count; i++) {
            Variable v = nodeToValue(nodeList.item(i));
            list.add(v.getValue());
        }
        return new Variable(list);
    }
    
    private static Variable nodeToValue(Node node) {
        int childElementCount = XmlUtils.getChildElementCount(node);
        if (childElementCount == 0) {
            // hack assuming this is the most common "intent"
            return new Variable(node.getTextContent());
        }
        if (node.getNodeType() == Node.DOCUMENT_NODE) {
            return new Variable(node);
        } else { // make sure we create a fresh doc else future xpath would run against original root
            return new Variable(XmlUtils.toNewDocument(node));
        }
    }
    
    public Variable evalJsonPathOnVariableByName(String name, String path) {
        return evalJsonPath(vars.get(name), path);
    }
    
    public Variable evalXmlPathOnVariableByName(String name, String path) {
        return evalXmlPath(vars.get(name), path);
    }
    
    public Variable evalKarateExpression(String text) {
        text = StringUtils.trimToNull(text);
        if (text == null) {
            return Variable.NULL;
        }
        Variable varValue = getIfVariableReference(text);
        if (varValue != null) {
            return varValue;
        }
        boolean callOnce = isCallOnceSyntax(text);
        if (callOnce || isCallSyntax(text)) { // special case in form "callBegin foo arg"
            if (callOnce) {
                text = text.substring(9);
            } else {
                text = text.substring(5);
            }
            StringUtils.Pair pair = parseCallArgs(text);
            Variable called = evalKarateExpression(pair.left);
            if (callOnce) {
                return callWithCache(pair.left, called, pair.right, false);
            } else {
                return call(called, pair.right, false);
            }
        } else if (isDollarPrefixedJsonPath(text)) {
            return evalJsonPathOnVariableByName(VariableNames.RESPONSE, text);
        } else if (isGetSyntax(text) || isDollarPrefixed(text)) { // special case in form
            // get json[*].path
            // $json[*].path
            // get /xml/path
            // get xpath-function(expression)
            int index = -1;
            if (text.startsWith("$")) {
                text = text.substring(1);
            } else if (text.startsWith("get[")) {
                int pos = text.indexOf(']');
                index = Integer.valueOf(text.substring(4, pos));
                text = text.substring(pos + 2);
            } else {
                text = text.substring(4);
            }
            String left;
            String right;
            if (isDollarPrefixedJsonPath(text)) { // edge case get[0] $..foo
                left = VariableNames.RESPONSE;
                right = text;
            } else if (isVariableAndSpaceAndPath(text)) {
                int pos = text.indexOf(' ');
                right = text.substring(pos + 1);
                left = text.substring(0, pos);
            } else {
                StringUtils.Pair pair = parseVariableAndPath(text);
                left = pair.left;
                right = pair.right;
            }
            Variable sv;
            if (isXmlPath(right) || isXmlPathFunction(right)) {
                sv = evalXmlPathOnVariableByName(left, right);
            } else {
                sv = evalJsonPathOnVariableByName(left, right);
            }
            if (index != -1 && sv.isList()) {
                List list = sv.getValue();
                if (!list.isEmpty()) {
                    return new Variable(list.get(index));
                }
            }
            return sv;
        } else if (isJson(text)) {
            Json json = new Json(text);
            return evalEmbeddedExpressions(new Variable(json.asMapOrList()));
        } else if (isXml(text)) {
            Document doc = XmlUtils.toXmlDoc(text);
            return evalEmbeddedExpressions(new Variable(doc));
        } else if (isXmlPath(text)) {
            return evalXmlPathOnVariableByName(VariableNames.RESPONSE, text);
        } else {
            if (isJavaScriptFunction(text)) {
                text = "(" + text + ")";
            }
            // js expressions e.g. foo, foo(bar), foo.bar, foo + bar, foo + '', 5, true
            // including function declarations e.g. function() { }
            return eval(text);
        }
    }
    
}