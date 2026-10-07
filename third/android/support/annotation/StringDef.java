package android.support.annotation;
import java.lang.annotation.*;
public @interface StringDef { String[] value() default {}; boolean flag() default false; }
