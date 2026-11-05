package com.onlinehomework.file.application;
import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FileRulesTest {
 private final FileRules rules=new FileRules();
 @Test void acceptsExactTwentyMiBAndRejectsOneByteOver(){assertDoesNotThrow(()->rules.validateDeclaration("x.pdf","application/pdf",20_971_520)); var e=assertThrows(DomainException.class,()->rules.validateDeclaration("x.pdf","application/pdf",20_971_521)); assertEquals("FILE_TOO_LARGE",e.code());}
 @Test void rejectsUnknownOrMismatchedContent(){var e=assertThrows(DomainException.class,()->rules.validateDeclaration("x.exe","application/octet-stream",1));assertEquals("UNSUPPORTED_FILE_TYPE",e.code()); var m=assertThrows(DomainException.class,()->rules.validateDeclaration("x.pdf","image/png",1));assertEquals("FILE_CONTENT_MISMATCH",m.code());}
 @Test void actualObjectSizeAndDetectedTypeAreRechecked(){var ok=new PrivateObjectStorage.StoredObject("v",20_971_520,"application/pdf","application/pdf","x"); assertDoesNotThrow(()->rules.validateActual("x.pdf","application/pdf",20_971_520,ok)); var bad=new PrivateObjectStorage.StoredObject("v",20_971_521,"application/pdf","application/pdf","x"); assertEquals("FILE_TOO_LARGE",assertThrows(DomainException.class,()->rules.validateActual("x.pdf","application/pdf",20_971_521,bad)).code());}
}
