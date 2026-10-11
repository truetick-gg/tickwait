package gg.truetick.tickwait;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LabelsTest {
    @Test
    void cleansColourCodesControlsAndLength() {
        assertEquals("Apex Hosting", Labels.clean("§aApex §lHosting"));
        assertEquals("a b", Labels.clean("a\n\t  b"));
        assertEquals("", Labels.clean(null));
        // a right-to-left override and a zero-width space would rearrange the report page
        assertEquals("Host abc", Labels.clean("Host \u202Eabc\u200B"));
        String long70 = new String(new char[70]).replace('\0', 'x');
        assertEquals(60, Labels.clean(long70).length());
    }
}
