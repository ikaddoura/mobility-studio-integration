package de.mobilitystudio.config; // Or your appropriate package

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigWriter;

/**
 * A dialog for viewing and editing raw MATSim config XML with syntax highlighting.
 */
public class EditorDialogConfigXml extends JDialog {

    private static final long serialVersionUID = 3L;
    private final JTextPane textPane;
    private String xmlText;
    private boolean applied = false;
    private String lastSearchTerm;

    private final UndoManager undoManager;
    
    private final JButton prevButton;
    private final JButton nextButton;

    // Regex for basic XML syntax highlighting
    private static final Pattern XML_TAG_PATTERN = Pattern.compile("(<[a-zA-Z0-9_\\-./]+)|(</[a-zA-Z0-9_\\-]+>)|(/>)");
    private static final Pattern XML_ATTRIBUTE_PATTERN = Pattern.compile("\\s+([a-zA-Z0-9_\\-]+)=|\n");
    private static final Pattern XML_VALUE_PATTERN = Pattern.compile("\"(.*?)\"");
    private static final Pattern XML_COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    public EditorDialogConfigXml(JDialog owner, Config config) {
        super(owner, "XML Source Editor", true);

        StringWriter writer = new StringWriter();
        new ConfigWriter(config).writeStream(writer);
        this.xmlText = writer.toString();

        textPane = new JTextPane();
        textPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        textPane.setEditable(true);

        undoManager = new UndoManager();
        textPane.getDocument().addUndoableEditListener(new UndoableEditListener() {
            @Override
            public void undoableEditHappened(UndoableEditEvent e) {
                undoManager.addEdit(e.getEdit());
            }
        });

        applySyntaxHighlighting(this.xmlText);
        // After initial text is set, clear the undo history so the user can't "undo" the file loading.
        undoManager.discardAllEdits();

        JScrollPane scrollPane = new JScrollPane(textPane);

        // --- Buttons ---
        JButton applyButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        JButton searchButton = new JButton("Search (Ctrl+F)");
        JButton undoButton = new JButton("Undo (Ctrl+Z)");  
        JButton redoButton = new JButton("Redo (Ctrl+Y)");  
        prevButton = new JButton("Previous");     
        nextButton = new JButton("Next");         

        prevButton.setEnabled(false);
        nextButton.setEnabled(false);
        
        applyButton.addActionListener(e -> onApply());
        cancelButton.addActionListener(e -> onCancel());
        searchButton.addActionListener(e -> onSearch());

        undoButton.addActionListener(e -> {
            try {
                if (undoManager.canUndo()) {
                    undoManager.undo();
                }
            } catch (CannotUndoException ex) {
                // Optional: log exception
            }
        });

        redoButton.addActionListener(e -> {
            try {
                if (undoManager.canRedo()) {
                    undoManager.redo();
                }
            } catch (CannotRedoException ex) {
                // Optional: log exception
            }
        });
        
        prevButton.addActionListener(e -> findPrevious());
        nextButton.addActionListener(e -> findNext());

        // --- Layout ---
        JPanel leftButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        leftButtonPanel.add(undoButton);
        leftButtonPanel.add(redoButton);
        leftButtonPanel.add(searchButton);
        leftButtonPanel.add(prevButton);
        leftButtonPanel.add(nextButton);

        JPanel rightButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        rightButtonPanel.add(applyButton);
        rightButtonPanel.add(cancelButton);
        
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(leftButtonPanel, BorderLayout.WEST);
        southPanel.add(rightButtonPanel, BorderLayout.EAST);

        setLayout(new BorderLayout(10, 10));
        add(scrollPane, BorderLayout.CENTER);
        add(southPanel, BorderLayout.SOUTH);

        setupKeyBindings();

        setSize(new Dimension(800, 700));
        setLocationRelativeTo(owner);
    }

    /**
     * Sets up keyboard shortcuts for the dialog (Search, Undo, Redo) that are
     * platform-aware (Cmd on macOS, Ctrl elsewhere).
     */
    private void setupKeyBindings() {
        // Get the platform-specific menu shortcut key mask (Cmd on Mac, Ctrl on others)
        final int shortcutKeyMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        // Get the root pane to ensure the binding works even if a button has focus
        JRootPane rootPane = this.getRootPane();
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();

        // Search: Cmd+F or Ctrl+F
        KeyStroke findKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_F, shortcutKeyMask);
        inputMap.put(findKeyStroke, "searchAction");
        actionMap.put("searchAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onSearch();
            }
        });

        // Use the text pane's maps for Undo/Redo as they are text-specific actions
        InputMap textInputMap = textPane.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap textActionMap = textPane.getActionMap();

        // Undo: Cmd+Z or Ctrl+Z
        KeyStroke undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutKeyMask);
        textInputMap.put(undoKeyStroke, "undoAction");
        textActionMap.put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) {
                    undoManager.undo();
                }
            }
        });

        // Create a single shared action for Redo
        AbstractAction redoAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) {
                    undoManager.redo();
                }
            }
        };
        textActionMap.put("redoAction", redoAction);

        // Redo: Cmd+Y or Ctrl+Y (works on all platforms)
        KeyStroke redoKeyStrokeY = KeyStroke.getKeyStroke(KeyEvent.VK_Y, shortcutKeyMask);
        textInputMap.put(redoKeyStrokeY, "redoAction");

        // Redo (macOS standard alternative): Cmd+Shift+Z
        KeyStroke redoKeyStrokeShiftZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutKeyMask | InputEvent.SHIFT_DOWN_MASK);
        textInputMap.put(redoKeyStrokeShiftZ, "redoAction");
    }

    private void onApply() {
        this.xmlText = textPane.getText();
        this.applied = true;
        dispose();
    }

    private void onCancel() {
        this.applied = false;
        dispose();
    }

    private void onSearch() {
    	Object result = JOptionPane.showInputDialog(
                this,                             // parentComponent
                "Find text:",                     // message
                "Search",                         // title
                JOptionPane.QUESTION_MESSAGE,     // messageType
                null,                             // icon (use default)
                null,                             // selectionValues (for free text input)
                this.lastSearchTerm               // initialSelectionValue
            );
    	
        String searchTerm = (result == null) ? null : result.toString();
    	
        if (searchTerm != null && !searchTerm.isEmpty()) {
            this.lastSearchTerm = searchTerm;
            this.prevButton.setEnabled(true);
            this.nextButton.setEnabled(true);
            // Start search from the beginning for a new term
            textPane.setCaretPosition(0);
            findNext();
        } else if (searchTerm != null) { // User entered an empty string
             this.lastSearchTerm = null;
             this.prevButton.setEnabled(false);
             this.nextButton.setEnabled(false);
        }
        // If the user clicks "Cancel" (searchTerm is null), we do nothing, preserving the last search.
    }
    
    private void findNext() {
        if (this.lastSearchTerm == null || this.lastSearchTerm.isEmpty()) {
            onSearch(); // Prompt for a term if one isn't set.
            return;
        }

        String content = textPane.getText();
        // Start search from the end of the current selection to find the next distinct match.
        int startPos = textPane.getSelectionEnd();

        // Perform a case-insensitive search.
        String contentLower = content.toLowerCase();
        String termLower = this.lastSearchTerm.toLowerCase();

        int index = contentLower.indexOf(termLower, startPos);

        if (index == -1) { // Not found from caret onwards.
            int choice = JOptionPane.showConfirmDialog(this,
                "Reached the end of the file. Continue search from the beginning?",
                "Search", JOptionPane.YES_NO_OPTION);

            if (choice == JOptionPane.YES_OPTION) {
                index = contentLower.indexOf(termLower, 0); // Search from the top.
            }
        }

        if (index != -1) {
            // Found the text, so select it.
            textPane.requestFocusInWindow();
            textPane.select(index, index + this.lastSearchTerm.length());
        } else {
            JOptionPane.showMessageDialog(this, "Text '" + this.lastSearchTerm + "' not found.", "Search Result", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * --- NEW METHOD ---
     * Finds the previous occurrence of the stored search term, wrapping around if necessary.
     */
    private void findPrevious() {
        if (this.lastSearchTerm == null || this.lastSearchTerm.isEmpty()) {
            onSearch(); // Prompt for a term if one isn't set.
            return;
        }

        String content = textPane.getText();
        // Start search from right before the beginning of the current selection.
        int startPos = textPane.getSelectionStart() - 1;

        // Perform a case-insensitive search.
        String contentLower = content.toLowerCase();
        String termLower = this.lastSearchTerm.toLowerCase();

        int index = contentLower.lastIndexOf(termLower, startPos);

        if (index == -1) { // Not found from caret backwards.
             int choice = JOptionPane.showConfirmDialog(this,
                "Reached the beginning of the file. Continue search from the end?",
                "Search", JOptionPane.YES_NO_OPTION);

            if (choice == JOptionPane.YES_OPTION) {
                index = contentLower.lastIndexOf(termLower, content.length()); // Search from the bottom up.
            }
        }

        if (index != -1) {
            // Found the text, so select it.
            textPane.requestFocusInWindow();
            textPane.select(index, index + this.lastSearchTerm.length());
        } else {
            JOptionPane.showMessageDialog(this, "Text '" + this.lastSearchTerm + "' not found.", "Search Result", JOptionPane.INFORMATION_MESSAGE);
        }
    }


    /**
     * Returns the edited XML text if 'Apply' was clicked, otherwise null.
     */
    public String getUpdatedXmlText() {
        return applied ? this.xmlText : null;
    }
    
    /**
     * Applies syntax highlighting to the text in the JTextPane.
     */
    private void applySyntaxHighlighting(String text) {
        textPane.setText(text);
        
        StyleContext styleContext = StyleContext.getDefaultStyleContext();
        
        // Define styles
        AttributeSet tagStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(0, 0, 150)); // Dark Blue for tags
        AttributeSet attrStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(150, 0, 0)); // Dark Red for attributes
        AttributeSet valueStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(0, 128, 0)); // Green for values
        AttributeSet commentStyle = styleContext.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, Color.GRAY);
        styleContext.addAttribute(commentStyle, StyleConstants.Italic, true);
        
        // Apply styles using regex
        highlightWithPattern(XML_COMMENT_PATTERN, commentStyle);
        highlightWithPattern(XML_TAG_PATTERN, tagStyle);
        highlightWithPattern(XML_ATTRIBUTE_PATTERN, attrStyle);
        highlightWithPattern(XML_VALUE_PATTERN, valueStyle);
    }

    private void highlightWithPattern(Pattern pattern, AttributeSet style) {
        Matcher matcher = pattern.matcher(textPane.getText());
        while(matcher.find()) {
            textPane.getStyledDocument().setCharacterAttributes(matcher.start(), matcher.end() - matcher.start(), style, false);
        }
    }
}