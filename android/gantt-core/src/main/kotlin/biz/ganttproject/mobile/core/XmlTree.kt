/*
 * GanttProject Mobile — read/edit GanttProject files on Android.
 * Copyright (C) 2026 GanttProject Mobile contributors
 * Licensed under the GNU General Public License v3 or later. See LICENSE.
 */
package biz.ganttproject.mobile.core

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

/*
 * A minimal XML tree that preserves everything a project file carries.
 *
 * Why not `org.w3c.dom`: the JDK and Android DOM implementations store
 * attributes in a `NamedNodeMap` that is *already* alphabetically ordered by
 * the time parsing finishes, and the standard serialiser writes them that
 * way. Attribute order carries no meaning in XML, so nothing breaks — but
 * every save would rewrite every element of the file, and desktop
 * GanttProject would reorder it all back on its next save. Project files
 * that live in version control or a synced folder would produce an
 * unreadable diff on each round trip.
 *
 * SAX reports attributes in document order and is available on Android
 * (StAX is not), so the tree is built from SAX and written by hand. That
 * also puts the XML declaration, indentation and CDATA handling under our
 * control instead of a vendor serialiser's.
 */

sealed interface XmlNode

/**
 * An element. [attributes] is insertion-ordered: updating an existing
 * attribute keeps its position, a new one is appended — which is what makes
 * an edited file differ from the original only in the lines actually edited.
 */
class XmlElement(val name: String) : XmlNode {
  val attributes: LinkedHashMap<String, String> = LinkedHashMap()
  val children: MutableList<XmlNode> = mutableListOf()

  fun attr(name: String): String? = attributes[name]

  /** Attribute value or the empty string, mirroring DOM's `getAttribute`. */
  fun attrOrEmpty(name: String): String = attributes[name] ?: ""

  fun setAttr(name: String, value: String) {
    attributes[name] = value
  }

  /** Direct child elements with the given tag name, in document order. */
  fun childElements(tagName: String): List<XmlElement> =
    children.filterIsInstance<XmlElement>().filter { it.name == tagName }

  fun firstChildElement(tagName: String): XmlElement? =
    children.filterIsInstance<XmlElement>().firstOrNull { it.name == tagName }

  fun removeChild(child: XmlNode) {
    children.remove(child)
  }

  fun insertBefore(child: XmlNode, reference: XmlNode?) {
    val index = reference?.let { children.indexOf(it) } ?: -1
    if (index < 0) children.add(child) else children.add(index, child)
  }

  /** Concatenated text of all direct text children, CDATA included. */
  fun textContent(): String =
    children.filterIsInstance<XmlText>().joinToString("") { it.value }
}

/** Character data. [cdata] records that it arrived inside a CDATA section. */
class XmlText(val value: String, val cdata: Boolean = false) : XmlNode

class XmlComment(val value: String) : XmlNode

// ------------------------------------------------------------------ Parsing

object XmlParser {

  /**
   * Parses [bytes] into a tree.
   *
   * External entities and DTDs are disabled: a project file may arrive from
   * a shared folder or a messaging app, and it must not be able to make the
   * parser read other files off the device (XXE).
   *
   * @throws SAXException if the input is not well-formed XML.
   */
  fun parse(bytes: ByteArray): XmlElement {
    val factory = SAXParserFactory.newInstance().apply {
      isNamespaceAware = false
      runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
      runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
      runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
      runCatching { setFeature("http://xml.org/sax/features/namespaces", false) }
    }
    val reader = factory.newSAXParser().xmlReader
    val handler = TreeBuilder()
    reader.contentHandler = handler
    reader.errorHandler = handler
    // Without a lexical handler, CDATA arrives as ordinary characters and is
    // written back escaped. Still valid XML and still the same text, so a
    // parser that refuses the property degrades rather than fails.
    runCatching {
      reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
    }
    // Entity resolution is already off; this is belt and braces for parsers
    // that ignore the feature flags above.
    reader.entityResolver = EntityResolver { _, _ ->
      InputSource(ByteArrayInputStream(ByteArray(0)))
    }
    reader.parse(InputSource(ByteArrayInputStream(bytes)))
    return handler.root ?: throw SAXException("document has no root element")
  }

  private class TreeBuilder : DefaultHandler(), LexicalHandler {
    var root: XmlElement? = null
    private val stack = ArrayDeque<XmlElement>()
    private val text = StringBuilder()
    private var inCdata = false

    override fun startElement(uri: String?, localName: String?, qName: String, atts: Attributes) {
      flushText()
      val element = XmlElement(qName)
      // SAX hands attributes back in document order; this is the whole
      // reason for building the tree ourselves.
      for (i in 0 until atts.length) {
        element.attributes[atts.getQName(i)] = atts.getValue(i)
      }
      stack.lastOrNull()?.children?.add(element)
      if (root == null) root = element
      stack.addLast(element)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
      flushText()
      stack.removeLastOrNull()
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
      text.appendRange(ch, start, start + length)
    }

    override fun startCDATA() {
      flushText()
      inCdata = true
    }

    override fun endCDATA() {
      flushText()
      inCdata = false
    }

    override fun comment(ch: CharArray, start: Int, length: Int) {
      flushText()
      stack.lastOrNull()?.children?.add(XmlComment(String(ch, start, length)))
    }

    private fun flushText() {
      if (text.isEmpty()) return
      val value = text.toString()
      text.setLength(0)
      val parent = stack.lastOrNull() ?: return
      // Indentation between elements carries no information and would fight
      // with re-indentation on write. An empty CDATA section is kept because
      // its presence is deliberate.
      if (!inCdata && value.isBlank()) return
      parent.children.add(XmlText(value, inCdata))
    }

    // Unused lexical events.
    override fun startDTD(name: String?, publicId: String?, systemId: String?) = Unit
    override fun endDTD() = Unit
    override fun startEntity(name: String?) = Unit
    override fun endEntity(name: String?) = Unit
  }
}

// ------------------------------------------------------------------ Writing

object XmlWriter {

  private const val INDENT = "    "

  /**
   * Serialises [root] with the same declaration and four-space indentation
   * desktop GanttProject uses, so a file written here looks like one written
   * there.
   */
  fun write(root: XmlElement): String {
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    writeElement(root, sb, 0)
    return sb.toString()
  }

  private fun writeElement(element: XmlElement, sb: StringBuilder, depth: Int) {
    val pad = INDENT.repeat(depth)
    sb.append(pad).append('<').append(element.name)
    for ((name, value) in element.attributes) {
      sb.append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"')
    }

    val textChildren = element.children.filterIsInstance<XmlText>()
    val elementChildren = element.children.filterIsInstance<XmlElement>()
    val commentChildren = element.children.filterIsInstance<XmlComment>()

    if (element.children.isEmpty()) {
      sb.append("/>").append('\n')
      return
    }

    // Text-only content stays on one line, which is how notes and timeline
    // values appear in files written by the desktop.
    if (elementChildren.isEmpty() && commentChildren.isEmpty() && textChildren.isNotEmpty()) {
      sb.append('>')
      textChildren.forEach { writeText(it, sb) }
      sb.append("</").append(element.name).append('>').append('\n')
      return
    }

    sb.append('>').append('\n')
    for (child in element.children) {
      when (child) {
        is XmlElement -> writeElement(child, sb, depth + 1)
        is XmlComment ->
          sb.append(INDENT.repeat(depth + 1)).append("<!--").append(child.value).append("-->").append('\n')
        is XmlText ->
          if (child.cdata || child.value.isNotBlank()) {
            sb.append(INDENT.repeat(depth + 1))
            writeText(child, sb)
            sb.append('\n')
          }
      }
    }
    sb.append(pad).append("</").append(element.name).append('>').append('\n')
  }

  private fun writeText(node: XmlText, sb: StringBuilder) {
    if (node.cdata) {
      // "]]>" cannot appear inside a CDATA section, so a value containing it
      // has to be split across two sections.
      sb.append("<![CDATA[").append(node.value.replace("]]>", "]]]]><![CDATA[>")).append("]]>")
    } else {
      sb.append(escapeText(node.value))
    }
  }

  private fun escapeText(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private fun escapeAttribute(value: String): String =
    value.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      // Line breaks and tabs inside an attribute are normalised to spaces by
      // any conforming parser, so they must be written as character
      // references to come back unchanged.
      .replace("\n", "&#10;")
      .replace("\r", "&#13;")
      .replace("\t", "&#9;")
}
