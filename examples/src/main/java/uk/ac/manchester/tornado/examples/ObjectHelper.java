package uk.ac.manchester.tornado.examples;

import java.util.ArrayList;

/**
 * Class that stores information about Object types. This information includes
 * the types of the Object class fields and the functions associated with the
 * object. This information will be used during code generation to construct a
 * struct equivalent to said object.
 */

public class ObjectHelper {

    public ArrayList objectClassFields;

    public ArrayList objectClassMethods;

    ObjectHelper(ArrayList objectClassFields, ArrayList objectClassMethods) {
        this.objectClassFields = objectClassFields;
        this.objectClassMethods = objectClassMethods;
    }

}
