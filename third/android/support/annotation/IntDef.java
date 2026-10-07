package android.support.annotation;
import java.lang.annotation.*;
public @interface IntDef { long[] value() default {}; boolean flag() default false; boolean open() default false; }
